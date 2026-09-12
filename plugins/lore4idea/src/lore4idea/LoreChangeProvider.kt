// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManagerGate
import com.intellij.openapi.vcs.changes.ChangeProvider
import com.intellij.openapi.vcs.changes.ChangelistBuilder
import com.intellij.openapi.vcs.changes.CurrentContentRevision
import com.intellij.openapi.vcs.changes.VcsDirtyScope
import com.intellij.vcsUtil.VcsUtil
import lore4idea.commands.LoreProtocol

internal class LoreChangeProvider(private val project: Project) : ChangeProvider {
  override fun isModifiedDocumentTrackingRequired() = false

  override fun getChanges(scope: VcsDirtyScope, builder: ChangelistBuilder, progress: ProgressIndicator, gate: ChangeListManagerGate) {
    val repositories = project.service<LoreRepositoryService>()
    for (root in scope.affectedContentRoots) {
      progress.checkCanceled()
      val status = runBlockingCancellable { repositories.withClient(root) { status() } }
      val revision = LoreRevisionNumber(status.revision)
      for (entry in status.files.distinctBy { it.path }) {
        progress.checkCanceled()
        if (entry.nodeType != "file") continue
        val path = VcsUtil.getFilePath(LoreProtocol.resolvePath(root.toNioPath(), entry.path).toString(), false)
        if (!scope.belongsTo(path)) continue
        if (entry.action == "add" && !entry.staged) {
          builder.processUnversionedFile(path)
          continue
        }
        val before = entry.beforePath?.let {
          val beforePath = VcsUtil.getFilePath(LoreProtocol.resolvePath(root.toNioPath(), it).toString(), false)
          LoreContentRevision(project, root, beforePath, revision)
        }
        val after = if (entry.action == "delete") null else CurrentContentRevision.create(path)
        val fileStatus = when {
          entry.conflict -> FileStatus.MERGED_WITH_CONFLICTS
          before == null -> FileStatus.ADDED
          after == null -> FileStatus.DELETED
          else -> FileStatus.MODIFIED
        }
        builder.processChange(Change(before, after, fileStatus), LoreVcs.KEY)
      }
    }
  }
}
