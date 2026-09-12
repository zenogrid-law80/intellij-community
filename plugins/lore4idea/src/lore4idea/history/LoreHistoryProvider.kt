// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.history

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.history.VcsAbstractHistorySession
import com.intellij.openapi.vcs.history.VcsAppendableHistorySessionPartner
import com.intellij.openapi.vcs.history.VcsDependentHistoryComponents
import com.intellij.openapi.vcs.history.VcsFileRevision
import com.intellij.openapi.vcs.history.VcsHistoryProvider
import com.intellij.openapi.vcs.history.VcsHistorySession
import com.intellij.openapi.vfs.VirtualFile
import lore4idea.LoreRepositoryService
import lore4idea.LoreRevisionNumber
import lore4idea.LoreSettings
import lore4idea.commands.LoreHistoryEntry
import lore4idea.log.loreUserIdentity
import java.util.Date
import javax.swing.JComponent

internal class LoreHistoryProvider(private val project: Project) : VcsHistoryProvider {
  override fun getUICustomization(session: VcsHistorySession, forShortcutRegistration: JComponent) =
    VcsDependentHistoryComponents.createOnlyColumns(emptyArray())
  override fun getAdditionalActions(refresher: Runnable): Array<AnAction> = emptyArray()
  override fun isDateOmittable() = false
  override fun getHelpId(): String? = null
  override fun supportsHistoryForDirectories() = false
  override fun getHistoryDiffHandler() = null
  override fun canShowHistoryFor(file: VirtualFile) = !file.isDirectory

  override fun createSessionFor(filePath: FilePath): VcsHistorySession = createSession(filePath)

  private fun createSession(filePath: FilePath): Session = runBlockingCancellable {
    val repositories = project.service<LoreRepositoryService>()
    val root = repositories.root(filePath)
    val path = repositories.relativePath(root, filePath)
    val limit = project.service<LoreSettings>().state.historyLimit.coerceIn(1, 10000)
    repositories.withClient(root) {
      val revision = LoreRevisionNumber(status(scan = false).revision)
      val entries = history(path, limit)
      val ids = entries.flatMap { listOfNotNull(it.metadata["created-by"], it.metadata["committed-by"]) }
        .filter(String::isNotBlank)
        .distinct()
      val names = try {
        users(ids)
      }
      catch (_: VcsException) {
        emptyMap()
      }
      Session(entries.map { FileRevision(project, root, it, names) }, revision)
    }
  }

  override fun reportAppendableHistory(path: FilePath, partner: VcsAppendableHistorySessionPartner) {
    partner.reportCreatedEmptySession(createSession(path))
  }

  private class Session(revisions: List<VcsFileRevision>, private val revision: LoreRevisionNumber) :
    VcsAbstractHistorySession(revisions, revision) {
    override fun calcCurrentRevisionNumber() = revision
    override fun copy() = Session(revisionList, revision)
    override fun isContentAvailable(revision: VcsFileRevision) = (revision as? FileRevision)?.entry?.deleted != true
  }

  private class FileRevision(
    private val project: Project,
    private val root: VirtualFile,
    val entry: LoreHistoryEntry,
    private val userNames: Map<String, String>,
  ) : VcsFileRevision {
    override fun getRevisionNumber() = LoreRevisionNumber(entry.revision)
    override fun getRevisionDate(): Date? = entry.metadata["timestamp"]?.toLongOrNull()?.let(::Date)
    override fun getAuthor(): String = loreUserIdentity(entry.metadata, userNames)
    override fun getCommitMessage(): String? = entry.metadata["message"]
    override fun getBranchName(): String? = null
    override fun getChangedRepositoryPath() = null
    override fun getContent(): ByteArray? = loadContent()
    override fun loadContent(): ByteArray? = if (entry.deleted) null else runBlockingCancellable {
      project.service<LoreRepositoryService>().withClient(root) { content(entry.path, entry.revision) }
    }
  }
}
