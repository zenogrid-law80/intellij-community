// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.log

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.Consumer
import com.intellij.vcs.log.Hash
import com.intellij.vcs.log.TimedVcsCommit
import com.intellij.vcs.log.VcsCommitMetadata
import com.intellij.vcs.log.VcsFullCommitDetails
import com.intellij.vcs.log.VcsLogObjectsFactory
import com.intellij.vcs.log.VcsLogProperties
import com.intellij.vcs.log.VcsLogProvider
import com.intellij.vcs.log.VcsLogRefManager
import com.intellij.vcs.log.VcsLogRefresher
import com.intellij.vcs.log.VcsRef
import com.intellij.vcs.log.VcsUser
import com.intellij.vcs.log.impl.LogDataImpl
import com.intellij.vcs.log.impl.VcsCommitMetadataImpl
import lore4idea.LoreBundle
import lore4idea.LoreContentRevision
import lore4idea.LoreRepositoryListener
import lore4idea.LoreRepositoryService
import lore4idea.LoreRevisionNumber
import lore4idea.LoreVcs
import lore4idea.commands.LoreBranch
import lore4idea.commands.LoreClient
import lore4idea.commands.LoreLogChange
import lore4idea.commands.LoreLogEntry
import java.nio.file.Path
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap

internal class LoreLogProvider(private val project: Project) : VcsLogProvider {
  private val factory = project.service<VcsLogObjectsFactory>()
  private val refManager = LoreRefManager()
  private val repositories = project.service<LoreRepositoryService>()
  private val userNames = ConcurrentHashMap<Path, ConcurrentHashMap<String, String>>()
  private val revisionCache = ConcurrentHashMap<Path, ConcurrentHashMap<String, LoreLogEntry>>()
  private val revisionDetailsCache = ConcurrentHashMap<Path, ConcurrentHashMap<String, LoreLogEntry>>()
  private val snapshots = ConcurrentHashMap<String, Snapshot>()

  override suspend fun readRecentCommits(
    root: VirtualFile,
    requirements: VcsLogProvider.Requirements,
    refsLoadingPolicy: VcsLogProvider.RefsLoadingPolicy,
  ): VcsLogProvider.DetailedLogData {
    val snapshot = load(root, requirements.commitCount)
    return LogDataImpl(snapshot.refs, snapshot.entries.map { metadata(root, it, snapshot.userNames) })
  }

  override fun readAllHashes(root: VirtualFile, commitConsumer: Consumer<in TimedVcsCommit>): VcsLogProvider.LogData {
    val snapshot = runBlockingCancellable { load(root, ALL_HISTORY_LIMIT) }
    val users = mutableSetOf<VcsUser>()
    for (entry in snapshot.entries) {
      commitConsumer.consume(factory.createTimedCommit(factory.createHash(entry.revision), parents(entry), timestamp(entry)))
      users.add(user(entry, snapshot.userNames))
    }
    return LogDataImpl(snapshot.refs, users)
  }

  override fun readMetadata(root: VirtualFile, hashes: List<String>, consumer: Consumer<in VcsCommitMetadata>) {
    runBlockingCancellable {
      repositories.withClient(root) {
        val entries = cachedRevisions(hashes)
        val names = resolveUserNames(entries)
        for (entry in entries) consumer.consume(metadata(root, entry, names))
      }
    }
  }

  override fun readFullDetails(root: VirtualFile, hashes: List<String>, commitConsumer: Consumer<in VcsFullCommitDetails>) {
    runBlockingCancellable {
      repositories.withClient(root) {
        val entries = cachedRevisionDetails(hashes)
        val names = resolveUserNames(entries)
        for (entry in entries) commitConsumer.consume(details(root, entry, names))
      }
    }
  }

  override val supportedVcs: VcsKey = LoreVcs.KEY
  override val referenceManager: VcsLogRefManager = refManager

  override fun subscribeToRootRefreshEvents(roots: Collection<VirtualFile>, refresher: VcsLogRefresher): Disposable {
    val connection = project.messageBus.connect()
    connection.subscribe(LoreRepositoryListener.TOPIC, object : LoreRepositoryListener {
      override fun repositoryChanged(root: VirtualFile) {
        if (root in roots) {
          snapshots.remove(root.url)
          refresher.refresh(root)
        }
      }
    })
    return connection
  }

  override fun getCurrentUser(root: VirtualFile): VcsUser? = runBlockingCancellable {
    repositories.withClient(root) {
      try {
        currentUser()?.let { (id, name) ->
          userNames.computeIfAbsent(this.root) { ConcurrentHashMap() }[id] = name
          factory.createUser(name, "")
        }
      }
      catch (_: VcsException) {
        null
      }
    }
  }

  override fun getContainingBranches(root: VirtualFile, commitHash: Hash): Collection<String> = runBlockingCancellable {
    load(root, ALL_HISTORY_LIMIT).containingBranches[commitHash.asString()].orEmpty()
  }

  override fun <T> getPropertyValue(property: VcsLogProperties.VcsLogProperty<T>): T? = null

  override fun getCurrentBranch(root: VirtualFile): String? = repositories.cachedBranch(root)

  override fun isFullHash(root: VirtualFile, hash: String): Boolean =
    hash.length == 64 && hash.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }

  private suspend fun load(root: VirtualFile, limit: Int): Snapshot {
    val requestedLimit = limit.coerceIn(1, ALL_HISTORY_LIMIT)
    snapshots[root.url]?.takeIf { it.limit >= requestedLimit }?.let { return it.limited(requestedLimit) }
    val loaded = repositories.withClient(root) {
    val branches = branchDetails()
    branches.firstOrNull { it.current }?.let { repositories.rememberBranch(root, it.name) }
    val entries = LinkedHashMap<String, LoreLogEntry>()
    val containingBranches = linkedMapOf<String, MutableSet<String>>()
    for (branch in branches) {
      val history = logWithUserNames(branch.name, requestedLimit)
      userNames.computeIfAbsent(this.root) { ConcurrentHashMap() }.putAll(history.userNames)
      for (entry in history.entries) {
        entries.putIfAbsent(entry.revision, entry)
        containingBranches.computeIfAbsent(entry.revision) { linkedSetOf() }.add(branch.name)
      }
    }
    val values = entries.values.sortedByDescending(::timestamp).take(requestedLimit)
    revisionCache.computeIfAbsent(this.root) { ConcurrentHashMap() }.putAll(values.associateBy { it.revision })
    Snapshot(values, references(root, branches), resolveUserNames(values), containingBranches, requestedLimit)
    }
    snapshots.compute(root.url) { _, current -> if (current == null || current.limit < loaded.limit) loaded else current }
    return snapshots[root.url]?.takeIf { it.limit >= requestedLimit }?.limited(requestedLimit) ?: loaded
  }

  private suspend fun LoreClient.cachedRevisions(hashes: List<String>): List<LoreLogEntry> {
    val cache = revisionCache.computeIfAbsent(root) { ConcurrentHashMap() }
    return hashes.map { hash -> cache[hash] ?: revision(hash).also { cache[hash] = it } }
  }

  private suspend fun LoreClient.cachedRevisionDetails(hashes: List<String>): List<LoreLogEntry> {
    val cache = revisionDetailsCache.computeIfAbsent(root) { ConcurrentHashMap() }
    return hashes.map { hash ->
      cache[hash] ?: revision(hash).also { entry ->
        cache[hash] = entry
        revisionCache.computeIfAbsent(root) { ConcurrentHashMap() }[hash] = entry
      }
    }
  }

  private suspend fun LoreClient.resolveUserNames(entries: Collection<LoreLogEntry>): Map<String, String> {
    val names = userNames.computeIfAbsent(root) { ConcurrentHashMap() }
    val ids = entries.flatMap { listOfNotNull(userId(it), it.metadata["committed-by"]?.takeIf(String::isNotBlank)) }.distinct()
    val unresolved = ids.filterNot(names::containsKey)
    if (unresolved.isNotEmpty()) {
      try {
        names.putAll(users(unresolved))
      }
      catch (_: VcsException) {
      }
    }
    return ids.associateWith { names[it]?.takeIf(String::isNotBlank) ?: it }
  }

  private fun references(root: VirtualFile, branches: List<LoreBranch>): Set<VcsRef> = branches.asSequence()
    .filterNot { it.latestRevision.all { character -> character == '0' } }
    .map { factory.createRef(factory.createHash(it.latestRevision), it.name, LoreRefManager.BRANCH, root) }
    .toSet()

  private fun metadata(root: VirtualFile, entry: LoreLogEntry, names: Map<String, String>): VcsCommitMetadata {
    val user = user(entry, names)
    val committer = createUser(loreUserIdentity(entry.metadata, names, committer = true))
    val message = message(entry)
    return factory.createCommitMetadata(factory.createHash(entry.revision), parents(entry), timestamp(entry), root,
                                        message.lineSequence().firstOrNull().orEmpty(), user.name, user.email, message,
                                        committer.name, committer.email, timestamp(entry))
  }

  private fun details(root: VirtualFile, entry: LoreLogEntry, names: Map<String, String>): VcsFullCommitDetails {
    val metadata = metadata(root, entry, names)
    val changes = entry.changes.map { change(root, entry, it) }
    return LoreCommitDetails(metadata, changes)
  }

  private fun change(root: VirtualFile, entry: LoreLogEntry, change: LoreLogChange): Change {
    val file = com.intellij.vcsUtil.VcsUtil.getFilePath(root.toNioPath().resolve(change.path).toString(), false)
    val current = LoreContentRevision(project, root, file, LoreRevisionNumber(entry.revision))
    val parent = entry.parents.firstOrNull()?.let { LoreContentRevision(project, root, file, LoreRevisionNumber(it)) }
    return when (change.action) {
      "add" -> Change(null, current, FileStatus.ADDED)
      "delete" -> Change(parent ?: current, null, FileStatus.DELETED)
      else -> Change(parent, current, FileStatus.MODIFIED)
    }
  }

  private fun parents(entry: LoreLogEntry): List<Hash> = entry.parents.map(factory::createHash)
  private fun timestamp(entry: LoreLogEntry): Long = entry.metadata["timestamp"]?.toLongOrNull() ?: 0
  private fun message(entry: LoreLogEntry): String = entry.metadata["message"].orEmpty()
  private fun userId(entry: LoreLogEntry): String? =
    entry.metadata["created-by"]?.takeIf(String::isNotBlank) ?: entry.metadata["committed-by"]?.takeIf(String::isNotBlank)

  private fun user(entry: LoreLogEntry, names: Map<String, String>): VcsUser {
    return createUser(loreUserIdentity(entry.metadata, names))
  }

  private fun createUser(identity: String): VcsUser {
    val match = USER_PATTERN.matchEntire(identity)
    return factory.createUser(match?.groupValues?.get(1)?.trim() ?: identity, match?.groupValues?.get(2).orEmpty())
  }

  private data class Snapshot(
    val entries: List<LoreLogEntry>,
    val refs: Set<VcsRef>,
    val userNames: Map<String, String>,
    val containingBranches: Map<String, Set<String>>,
    val limit: Int,
  ) {
    fun limited(requestedLimit: Int): Snapshot = if (entries.size <= requestedLimit) this else copy(entries = entries.take(requestedLimit))
  }

  companion object {
    private const val ALL_HISTORY_LIMIT = 50_000
    private val USER_PATTERN = Regex("(.*)\\s+<([^>]+)>")
  }
}

internal fun loreUserIdentity(metadata: Map<String, String>, names: Map<String, String>, committer: Boolean = false): String {
  val keys = if (committer) listOf("committed-by", "created-by") else listOf("created-by", "committed-by")
  val id = keys.firstNotNullOfOrNull { metadata[it]?.takeIf(String::isNotBlank) }
           ?: return LoreBundle.message("log.unknown.user")
  return names[id]?.takeIf(String::isNotBlank) ?: id
}

private class LoreCommitDetails(metadata: VcsCommitMetadata, private val changes: List<Change>) :
  VcsCommitMetadataImpl(metadata.id, metadata.parents, metadata.commitTime, metadata.root, metadata.subject, metadata.author,
                        metadata.fullMessage, metadata.committer, metadata.authorTime), VcsFullCommitDetails {
  override fun getChanges(): Collection<Change> = changes
  override fun getChanges(parent: Int): Collection<Change> = changes
}
