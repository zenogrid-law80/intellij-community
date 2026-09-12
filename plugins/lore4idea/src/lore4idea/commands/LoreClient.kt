// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.commands

import com.intellij.openapi.vcs.VcsException
import com.intellij.platform.eel.fs.createTemporaryFile
import com.intellij.platform.eel.getOrThrow
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApi
import lore4idea.LoreBundle
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal fun isValidLoreServerUrl(value: String): Boolean =
  value.contains("://") && !value.endsWith("://") && value.none { it.isWhitespace() }

internal class LoreClient(val root: Path, private val executor: LoreExecutor) {
  suspend fun command(vararg arguments: String): List<LoreEvent> = LoreProtocol.events(executor.execute(root, arguments.toList()))

  private suspend fun commandAccepting(failureAfter: String, vararg arguments: String): List<LoreEvent> =
    LoreProtocol.events(executor.execute(root, arguments.toList()), failureAfter)

  suspend fun status(scan: Boolean = true): LoreStatus = LoreProtocol.status(
    if (scan) command("--offline", "status", "--scan") else command("--offline", "status")
  ).also { status ->
    for (file in status.files) {
      if (file.path != "." || file.nodeType != "directory") LoreProtocol.resolvePath(root, file.path)
      if (file.fromPath.isNotEmpty()) LoreProtocol.resolvePath(root, file.fromPath)
    }
  }

  suspend fun stage(paths: Collection<String>) {
    for (path in paths.distinct()) {
      LoreProtocol.resolvePath(root, path)
      command("--offline", "stage", "--", "./$path")
    }
  }

  suspend fun commit(paths: Set<String>, message: String) {
    if (paths.isEmpty()) throw VcsException(LoreBundle.message("error.empty.commit"))
    paths.forEach { LoreProtocol.resolvePath(root, it) }
    checkCommit(status(), paths)
    stage(paths)
    checkCommit(status(scan = false), paths)
    commandAccepting("revisionCommitRevision", "--offline", "commit", "--", message)
  }

  private fun checkCommit(status: LoreStatus, paths: Set<String>) {
    if (status.merged) throw VcsException(LoreBundle.message("error.merge"))
    if (status.files.any { it.conflict }) throw VcsException(LoreBundle.message("error.conflict"))
    if (status.files.any { it.staged && it.nodeType !in setOf("file", "directory") }) {
      throw VcsException(LoreBundle.message("error.unsupported.node"))
    }
    if (status.files.any { it.staged && !belongsToSelection(it, paths) }) {
      throw VcsException(LoreBundle.message("error.staged"))
    }
  }

  private fun belongsToSelection(file: LoreFileStatus, paths: Set<String>): Boolean {
    if (file.nodeType == "directory") {
      val prefix = file.path.trimEnd('/') + "/"
      return file.path == "." || paths.any { it == file.path || it.startsWith(prefix) }
    }
    return file.path in paths && (file.action != "move" || file.fromPath in paths)
  }

  suspend fun revert(paths: Collection<String>) = resetPaths(paths)

  private suspend fun resetPaths(paths: Collection<String>) {
    val arguments = paths.distinct().map { path ->
      LoreProtocol.resolvePath(root, path)
      "./$path"
    }.toTypedArray()
    if (arguments.isEmpty()) return
    command("--offline", "unstage", "--", *arguments)
    command("--offline", "reset", "--purge", "--", *arguments)
  }

  suspend fun resetAll() {
    val current = status()
    if (current.merged) {
      command("--offline", "branch", "merge", "abort")
      return
    }
    val paths = current.files.asSequence()
      .map { it.path }
      .filter { it != "." }
      .distinct()
      .toList()
    resetPaths(paths)
  }

  suspend fun branchDetails(): List<LoreBranch> = LoreProtocol.branches(command("--offline", "branch", "list"))

  suspend fun remoteBranchDetails(): List<LoreBranch> = LoreProtocol.branches(command("--remote", "branch", "list"))

  suspend fun syncCounts(): LoreSyncCounts {
    val status = LoreProtocol.trackingStatus(command("status", "--revision-only"))
    val branch = status.branch
    if (!status.remoteAvailable || !status.remoteAuthorized) {
      throw VcsException(LoreBundle.message("error.count.remote"))
    }
    if (!status.remoteBranchExists) {
      return LoreSyncCounts(branch, 0, revisions(status.localRevision, false, branch).size)
    }
    if (status.localRevision == status.remoteRevision || !status.localAhead && !status.remoteAhead) {
      return LoreSyncCounts(branch, 0, 0)
    }
    if (status.remoteAhead && !status.localAhead) {
      return LoreSyncCounts(branch, revisionDistance(status.remoteRevisionNumber, status.localRevisionNumber), 0)
    }
    if (status.localAhead && !status.remoteAhead) {
      return LoreSyncCounts(branch, 0, revisionDistance(status.localRevisionNumber, status.remoteRevisionNumber))
    }

    val localEntries = revisions(status.localRevision, false, branch)
    val localRevisions = localEntries.map { it.revision }.toSet()
    val remoteRevisions = revisions(status.remoteRevision, true, branch).map { it.revision }.toSet()
    return LoreSyncCounts(branch, (remoteRevisions - localRevisions).size, (localRevisions - remoteRevisions).size)
  }

  private suspend fun revisions(revision: String, remote: Boolean, branch: String): List<LoreLogEntry> {
    if (revision.all { it == '0' }) return emptyList()
    val arguments = if (remote) {
      arrayOf("--remote", "history", "--branch", branch, COUNT_LIMIT.toString())
    }
    else {
      arrayOf("--offline", "history", "--revision", revision, COUNT_LIMIT.toString())
    }
    val entries = LoreProtocol.log(commandAccepting("revisionHistoryEntry", *arguments))
    if (entries.size >= COUNT_LIMIT || entries.firstOrNull()?.revision != revision) {
      throw VcsException(LoreBundle.message("error.count.history"))
    }
    val hashes = entries.map { it.revision }.toSet()
    if (entries.any { entry -> entry.parents.any { it !in hashes } }) {
      throw VcsException(LoreBundle.message("error.count.history"))
    }
    return entries
  }

  private fun revisionDistance(ahead: Long, behind: Long): Int {
    val distance = ahead - behind
    if (distance <= 0 || distance > Int.MAX_VALUE) throw VcsException(LoreBundle.message("error.count.history"))
    return distance.toInt()
  }

  suspend fun branches(): List<String> = branchDetails().map { it.name }

  suspend fun login(serverUrl: String) {
    if (!isValidLoreServerUrl(serverUrl)) throw VcsException(LoreBundle.message("dialog.login.url.invalid"))
    command("login", serverUrl)
  }

  suspend fun logout() {
    command("auth", "clear")
  }

  suspend fun createBranch(name: String) {
    if (name.isBlank()) throw VcsException(LoreBundle.message("dialog.branch.required"))
    requireClean()
    if (branchDetails().any { it.name == name }) throw VcsException(LoreBundle.message("error.branch.exists", name))
    command("--offline", "branch", "create", "--", name)
  }

  suspend fun createRemoteBranch(name: String) {
    createBranch(name)
    try {
      command("push", "--", name)
    }
    catch (error: VcsException) {
      throw VcsException(LoreBundle.message("error.branch.remote.create", name, error.message.orEmpty()), error)
    }
  }

  suspend fun switchBranch(name: String) {
    requireClean()
    command("branch", "switch", "--", name)
  }

  suspend fun deleteBranch(name: String) {
    requireInactiveBranch(name)
    command("--offline", "branch", "archive", "--local", "--", name)
  }

  suspend fun deleteRemoteBranch(name: String) {
    command("branch", "archive", "--remote", "--", name)
  }

  suspend fun archiveBranch(name: String) {
    requireInactiveBranch(name)
    command("branch", "archive", "--", name)
  }

  private suspend fun requireInactiveBranch(name: String) {
    if (status(scan = false).branch == name) throw VcsException(LoreBundle.message("error.branch.current", name))
  }

  suspend fun mergeBranch(source: String, target: String) {
    val current = status()
    if (current.branch != target) throw VcsException(LoreBundle.message("error.merge.branch.changed", target))
    if (source == target) throw VcsException(LoreBundle.message("error.merge.same.branch"))
    if (current.merged || current.files.isNotEmpty()) throw VcsException(LoreBundle.message("error.dirty"))
    commandAccepting("revisionCommitRevision", "--offline", "branch", "merge",
                     "--message", LoreBundle.message("merge.commit.message", source, target), "--", source)
    val result = status(scan = false)
    if (result.merged || result.files.any { it.conflict }) throw VcsException(LoreBundle.message("error.merge.pending"))
  }

  suspend fun abortMerge() {
    if (!status(scan = false).merged) throw VcsException(LoreBundle.message("error.merge.none"))
    command("--offline", "branch", "merge", "abort")
  }

  suspend fun sync() {
    requireClean()
    command("sync")
  }

  fun view(): String? = LoreViewFile(root).read()

  suspend fun viewFolders(parent: String, revision: String): LoreFolderListing {
    if (parent.isNotEmpty()) LoreProtocol.resolvePath(root, parent)
    if (revision.all { it == '0' }) return LoreFolderListing(revision, emptyList())
    val arguments = mutableListOf("--cache", "repository", "dump", "--revision", revision,
                                  "--max-depth", if (parent.isEmpty()) "1" else "2")
    if (parent.isNotEmpty()) arguments.add("--path=$parent")
    return parseLoreFolders(root, parent, revision, command(*arguments.toTypedArray()))
  }

  suspend fun saveFolderSelections(revision: String, changes: List<LoreFolderSelection>, apply: Boolean) {
    val latest = view()
    val rules = LoreFolderRules.merge(latest.orEmpty(), changes)
    if (!apply && rules == latest.orEmpty()) return
    saveView(latest, rules, apply, revision)
  }

  suspend fun saveView(expected: String?, content: String, apply: Boolean, expectedRevision: String? = null) {
    val view = LoreViewFile(root)
    if (view.read() != expected) throw VcsException(LoreBundle.message("error.view.changed"))
    val current = if (apply || expectedRevision != null) status(scan = apply) else null
    if (expectedRevision != null && current?.revision != expectedRevision) {
      throw VcsException(LoreBundle.message("error.view.revision"))
    }
    if (apply && current != null) checkViewChanges(current)
    view.write(expected, content)
    var validated = false
    try {
      val updated = status(scan = apply)
      if (apply && current != null) {
        if (updated.revision != current.revision || updated.branch != current.branch) {
          throw VcsException(LoreBundle.message("error.view.revision"))
        }
        checkViewChanges(updated)
      }
      validated = true
    }
    finally {
      if (!validated && view.read() == content) view.write(content, expected)
    }
    if (!apply || current == null || current.revision.all { it == '0' }) return
    try {
      command("--cache", "sync", "--reset", current.revision)
    }
    catch (error: VcsException) {
      throw VcsException(LoreBundle.message("error.view.apply", error.message.orEmpty()), error)
    }
  }

  private fun checkViewChanges(status: LoreStatus) {
    if (status.merged || status.files.any {
        it.action != "delete" || it.staged || it.conflict || Files.exists(LoreProtocol.resolvePath(root, it.path), NOFOLLOW_LINKS)
      }) {
      throw VcsException(LoreBundle.message("error.view.dirty"))
    }
  }

  suspend fun push() {
    command("push")
  }

  suspend fun discardUnpushedCommits() {
    val current = requireClean()
    val branch = current.branch
    val remote = remoteBranchDetails().firstOrNull { it.name == branch }
                 ?: throw VcsException(LoreBundle.message("error.remote.branch", branch))
    if (remote.latestRevision.any { it != '0' }) {
      val localHistory = revisions(current.revision, false, branch)
      if (localHistory.none { it.revision == remote.latestRevision }) {
        throw VcsException(LoreBundle.message("error.discard.diverged"))
      }
    }
    command("revision", "sync", "--reset", remote.latestRevision)
    command("--offline", "branch", "reset", remote.latestRevision, "--branch", branch)
  }

  private suspend fun requireClean(): LoreStatus {
    val status = status()
    if (status.merged || status.files.isNotEmpty()) throw VcsException(LoreBundle.message("error.dirty"))
    return status
  }

  suspend fun history(path: String, limit: Int): List<LoreHistoryEntry> {
    LoreProtocol.resolvePath(root, path)
    return LoreProtocol.history(command("--offline", "file", "history", "--", "./$path", limit.toString()))
  }

  suspend fun log(branch: String, limit: Int): List<LoreLogEntry> = LoreProtocol.log(
    commandAccepting("revisionHistory", "--offline", "history", "--branch", branch, limit.toString())
  )

  suspend fun revision(revision: String): LoreLogEntry {
    val entries = LoreProtocol.log(
      commandAccepting("revisionInfo", "--offline", "revision", "info", revision, "--delta", "--metadata")
    )
    return entries.singleOrNull() ?: throw VcsException(LoreBundle.message("error.protocol", "revisionInfo"))
  }

  suspend fun users(userIds: Collection<String>): Map<String, String> {
    if (userIds.isEmpty()) return emptyMap()
    val result = linkedMapOf<String, String>()
    for (chunk in userIds.distinct().chunked(USER_QUERY_SIZE)) {
      val arguments = listOf("auth", "info", "--") + chunk
      result.putAll(LoreProtocol.users(command(*arguments.toTypedArray())))
    }
    return result
  }

  suspend fun currentUser(): Pair<String, String>? = LoreProtocol.users(command("auth", "info")).entries.firstOrNull()?.toPair()

  suspend fun content(path: String, revision: String): ByteArray {
    LoreProtocol.resolvePath(root, path)
    val api = root.getEelDescriptor().toEelApi()
    val output = api.fs.createTemporaryFile().prefix("lore4idea-").suffix(".content").getOrThrow()
    try {
      command(*contentArguments(path, revision, output.toString()).toTypedArray())
      return Files.readAllBytes(output.asNioPath())
    }
    finally {
      Files.deleteIfExists(output.asNioPath())
    }
  }

  internal fun contentArguments(path: String, revision: String, output: String): List<String> =
    listOf("--cache", "file", "write", "--path", "./$path", "--revision", revision, "--output", output)

  companion object {
    private const val COUNT_LIMIT = 10001
    private const val USER_QUERY_SIZE = 100
  }
}
