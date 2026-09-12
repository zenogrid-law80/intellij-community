// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.diff.DiffProvider
import com.intellij.openapi.vcs.diff.ItemLatestState
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil

internal class LoreDiffProvider(private val project: Project) : DiffProvider {
  override fun getCurrentRevision(file: VirtualFile): LoreRevisionNumber? = revision(VcsUtil.getFilePath(file))
  override fun getLatestCommittedRevision(vcsRoot: VirtualFile): LoreRevisionNumber? = getCurrentRevision(vcsRoot)
  override fun getLastRevision(file: VirtualFile): ItemLatestState? = getLastRevision(VcsUtil.getFilePath(file))
  override fun getLastRevision(file: FilePath): ItemLatestState? {
    try {
      val repositories = project.service<LoreRepositoryService>()
      val root = repositories.root(file)
      val path = repositories.relativePath(root, file)
      return runBlockingCancellable {
        repositories.withClient(root) {
          val status = status(scan = false)
          val deleted = status.files.any { it.path == path && it.action == "delete" }
          ItemLatestState(LoreRevisionNumber(status.revision), !deleted, false)
        }
      }
    }
    catch (e: VcsException) {
      logger<LoreDiffProvider>().warn(e)
      return null
    }
  }

  override fun createFileContent(revisionNumber: VcsRevisionNumber, selectedFile: VirtualFile): LoreContentRevision? {
    val number = revisionNumber as? LoreRevisionNumber ?: return null
    val path = VcsUtil.getFilePath(selectedFile)
    return LoreContentRevision(project, project.service<LoreRepositoryService>().root(path), path, number)
  }

  private fun revision(file: FilePath): LoreRevisionNumber? {
    try {
      val repositories = project.service<LoreRepositoryService>()
      val root = repositories.root(file)
      return runBlockingCancellable { repositories.withClient(root) { LoreRevisionNumber(status(scan = false).revision) } }
    }
    catch (e: VcsException) {
      logger<LoreDiffProvider>().warn(e)
      return null
    }
  }
}
