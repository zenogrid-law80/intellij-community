// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinEnvironment
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcsUtil.VcsUtil

internal class LoreCheckinEnvironment(private val project: Project) : CheckinEnvironment {
  override fun getHelpId(): String? = null
  override fun getCheckinOperationName() = LoreBundle.message("operation.commit")
  override fun isRefreshAfterCommitNeeded() = true

  override fun commit(changes: List<Change>, commitMessage: String, commitContext: CommitContext, feedback: MutableSet<in String>): List<VcsException> {
    val paths = changes.flatMap { listOfNotNull(it.beforeRevision?.file, it.afterRevision?.file) }.distinct()
    return operate(paths) { selected ->
      commit(selected.toSet(), commitMessage)
      if (commitContext.isLorePushAfterCommit) {
        try {
          push()
        }
        catch (error: VcsException) {
          throw VcsException(LoreBundle.message("error.commit.push", error.message.orEmpty()), error)
        }
      }
    }
  }

  override fun scheduleMissingFileForDeletion(files: List<FilePath>): List<VcsException> = operate(files) { stage(it) }
  override fun scheduleUnversionedFilesForAddition(files: List<VirtualFile>): List<VcsException> =
    operate(files.map(VcsUtil::getFilePath)) { stage(it) }

  private fun operate(paths: List<FilePath>, operation: suspend lore4idea.commands.LoreClient.(List<String>) -> Unit): List<VcsException> {
    val errors = mutableListOf<VcsException>()
    val repositories = project.service<LoreRepositoryService>()
    try {
      val groups = paths.groupBy(repositories::root)
      for ((root, files) in groups) {
        try {
          val relative = files.map { repositories.relativePath(root, it) }
          runBlockingCancellable { repositories.withClient(root) { operation(relative) } }
        }
        catch (e: VcsException) {
          errors.add(e)
        }
        finally {
          repositories.refresh(root)
        }
      }
    }
    catch (e: VcsException) {
      errors.add(e)
    }
    return errors
  }
}
