// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.rollback.RollbackEnvironment
import com.intellij.openapi.vcs.rollback.RollbackProgressListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil

internal class LoreRollbackEnvironment(private val project: Project) : RollbackEnvironment {
  override fun getRollbackOperationName() = LoreBundle.message("operation.rollback")

  override fun rollbackChanges(changes: List<Change>, exceptions: MutableList<VcsException>, listener: RollbackProgressListener) {
    val files = changes.flatMap { listOfNotNull(it.afterRevision?.file, it.beforeRevision?.file) }.distinct()
    rollback(files, exceptions, listener)
  }

  override fun rollbackMissingFileDeletion(files: List<FilePath>, exceptions: MutableList<in VcsException>, listener: RollbackProgressListener) =
    rollback(files, exceptions, listener)

  override fun rollbackModifiedWithoutCheckout(files: List<VirtualFile>, exceptions: MutableList<in VcsException>, listener: RollbackProgressListener) =
    rollback(files.map(VcsUtil::getFilePath), exceptions, listener)

  private fun rollback(files: List<FilePath>, exceptions: MutableList<in VcsException>, listener: RollbackProgressListener) {
    val repositories = project.service<LoreRepositoryService>()
    try {
      for ((root, selected) in files.groupBy(repositories::root)) {
        try {
          val paths = selected.map { repositories.relativePath(root, it) }
          listener.checkCanceled()
          runBlockingCancellable { repositories.withClient(root) { revert(paths) } }
          selected.forEach(listener::accept)
        }
        catch (e: VcsException) {
          exceptions.add(e)
        }
        finally {
          repositories.refresh(root)
        }
      }
    }
    catch (e: VcsException) {
      exceptions.add(e)
    }
  }
}
