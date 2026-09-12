// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.actions

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.impl.ExpandableComboAction
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lore4idea.LoreBundle
import lore4idea.LoreRepositoryService
import lore4idea.rethrowCancellation

internal class LoreToolbarWidgetAction : ExpandableComboAction(), DumbAware {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val project = e.project
    val root = project?.let { selectedRoot(e, loreRoots(it)) }
    if (project == null || root == null) {
      e.presentation.isEnabledAndVisible = false
      return
    }
    val repositories = project.service<LoreRepositoryService>()
    val branch = repositories.cachedBranch(root)
    val counts = repositories.cachedSyncCounts(root)
    with(e.presentation) {
      isEnabledAndVisible = true
      icon = AllIcons.Vcs.Branch
      text = branch ?: LoreBundle.message("toolbar.loading")
      description = if (counts == null) repositories.syncCountsError(root) ?: LoreBundle.message("toolbar.sync.unavailable")
      else LoreBundle.message("toolbar.sync.description", counts.incoming, counts.outgoing)
    }
  }

  override fun createPopup(event: AnActionEvent): JBPopup? {
    val project = event.project ?: return null
    val root = selectedRoot(event, loreRoots(project)) ?: return null
    val repositories = project.service<LoreRepositoryService>()
    var remoteError: String? = null
    var merging = false
    val (current, branches, remoteBranches) = try {
      runWithModalProgressBlocking(project, LoreBundle.message("toolbar.loading.branches")) {
        repositories.withClient(root) {
          val status = status(scan = false)
          val current = status.branch
          merging = status.merged
          val local = branches()
          val remote = try {
            remoteBranchDetails().map { it.name }
          }
          catch (error: VcsException) {
            remoteError = error.message
            null
          }
          Triple(current, local, remote)
        }
      }
    }
    catch (error: Exception) {
      rethrowCancellation(error)
      NotificationGroupManager.getInstance().getNotificationGroup("Lore")
        .createNotification(error.message.orEmpty(), NotificationType.ERROR).notify(project)
      return null
    }
    repositories.rememberBranch(root, current)

    val group = DefaultActionGroup()
    val manager = ActionManager.getInstance()
    group.add(LoreLoginAction(root))
    group.add(LoreLogoutAction(root))
    group.addSeparator()
    group.add(LoreSyncAction(root))
    manager.getAction("CheckinProject")?.let(group::add)
    group.add(LorePushAction(root))
    manager.getAction("Lore.DiscardUnpushed")?.let(group::add)
    group.add(LoreResetAllAction(root))
    group.add(LoreRefreshAction(root))
    group.add(LoreViewAction(root))
    if (merging) group.add(LoreAbortMergeAction(root))
    group.addSeparator()
    val createBranch = LoreCreateBranchAction(root)
    createBranch.templatePresentation.text = LoreBundle.message("action.Lore.CreateBranch.text")
    group.add(createBranch)
    val createRemoteBranch = LoreCreateBranchAction(root, remote = true)
    createRemoteBranch.templatePresentation.text = LoreBundle.message("action.Lore.CreateRemoteBranch.text")
    group.add(createRemoteBranch)
    group.addSeparator()
    group.add(branchesGroup(root, current, branches, local = true))
    group.add(branchesGroup(root, current, remoteBranches, local = false, error = remoteError))

    return JBPopupFactory.getInstance().createActionGroupPopup(
      null,
      group,
      event.dataContext,
      JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
      true,
      ActionPlaces.getPopupPlace(ActionPlaces.VCS_TOOLBAR_WIDGET),
    )
  }

  private fun branchesGroup(
    root: VirtualFile,
    current: String,
    branches: List<String>?,
    local: Boolean,
    error: String? = null,
  ): DefaultActionGroup {
    val title = LoreBundle.message(if (local) "toolbar.local.branches" else "toolbar.remote.branches")
    val group = DefaultActionGroup(title, true)
    if (branches.isNullOrEmpty()) {
      val key = when {
        local -> "toolbar.local.empty"
        branches == null -> "toolbar.remote.unavailable"
        else -> "toolbar.remote.empty"
      }
      group.add(LorePopupInfoAction(LoreBundle.message(key), error))
    }
    else {
      branches.forEach { group.add(branchGroup(root, it, current, local)) }
    }
    return group
  }

  private fun branchGroup(root: VirtualFile, branch: String, current: String, local: Boolean): DefaultActionGroup {
    val group = DefaultActionGroup(branch, true)
    group.templatePresentation.icon = if (branch == current) AllIcons.Actions.Checked else AllIcons.Vcs.Branch
    group.add(LorePopupBranchAction(root, branch, branch == current))
    if (local && branch != current) {
      group.add(LoreMergeBranchAction(root, branch, current))
      group.addSeparator()
      group.add(LoreDeleteBranchAction(root, branch))
      group.add(LoreArchiveBranchAction(root, branch))
    }
    else if (!local) {
      group.addSeparator()
      group.add(LoreDeleteRemoteBranchAction(root, branch))
    }
    return group
  }

  private fun selectedRoot(event: AnActionEvent, roots: List<VirtualFile>): VirtualFile? {
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    return file?.let { selected -> roots.firstOrNull { VfsUtilCore.isAncestor(it, selected, false) } } ?: roots.firstOrNull()
  }
}

private class LorePopupInfoAction(private val label: String, private val detail: String?) : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(e: AnActionEvent) {
    e.presentation.text = label
    e.presentation.description = detail
    e.presentation.isEnabled = false
  }

  override fun actionPerformed(e: AnActionEvent) = Unit
}

private class LorePopupBranchAction(
  private val root: VirtualFile,
  private val branch: String,
  private val current: Boolean,
) : DumbAwareAction(branch, null, AllIcons.Vcs.Branch) {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.text = LoreBundle.message("action.Lore.SwitchBranch.text")
    e.presentation.isEnabled = !current
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    try {
      FileDocumentManager.getInstance().saveAllDocuments()
    }
    catch (error: Exception) {
      rethrowCancellation(error)
      NotificationGroupManager.getInstance().getNotificationGroup("Lore")
        .createNotification(error.message.orEmpty().ifBlank { error.javaClass.simpleName }, NotificationType.ERROR).notify(project)
      return
    }
    e.coroutineScope.launch(Dispatchers.IO) {
      try {
        project.service<LoreRepositoryService>().withClient(root) { switchBranch(branch) }
        NotificationGroupManager.getInstance().getNotificationGroup("Lore")
          .createNotification(LoreBundle.message("toolbar.branch.switched", branch), NotificationType.INFORMATION).notify(project)
      }
      catch (error: Exception) {
        rethrowCancellation(error)
        NotificationGroupManager.getInstance().getNotificationGroup("Lore")
          .createNotification(error.message.orEmpty(), NotificationType.ERROR).notify(project)
      }
      finally {
        if (!project.isDisposed) project.service<LoreRepositoryService>().refresh(root)
      }
    }
  }
}
