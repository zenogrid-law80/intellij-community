// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lore4idea.LoreBundle
import lore4idea.rethrowCancellation
import lore4idea.LoreRepositoryService
import lore4idea.LoreSettings
import lore4idea.LoreVcs
import lore4idea.commands.isValidLoreServerUrl
import org.jetbrains.annotations.Nls

internal class LoreActionGroup : DefaultActionGroup(), DumbAware {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.let { loreRoots(it).isNotEmpty() } == true
  }
}

internal abstract class LoreRepositoryAction(private val selectedRoot: VirtualFile? = null) : DumbAwareAction() {
  override fun getActionUpdateThread() = ActionUpdateThread.BGT
  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.let { loreRoots(it).isNotEmpty() } == true
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val root = try {
      val roots = loreRoots(project)
      if (roots.isEmpty()) return
      val selected = selectedRoot?.takeIf { it in roots } ?: if (roots.size == 1) roots.single() else {
        val dialog = LoreChoiceDialog(project, LoreBundle.message("dialog.root.title"), LoreBundle.message("dialog.root.label"),
                                      roots.map { it.presentableUrl })
        if (!dialog.showAndGet()) return
        roots.first { it.presentableUrl == dialog.selection }
      }
      if (saveDocumentsBeforeAction) FileDocumentManager.getInstance().saveAllDocuments()
      selected
    }
    catch (error: Exception) {
      rethrowCancellation(error)
      notify(project, error.message.orEmpty().ifBlank { error.javaClass.simpleName }, NotificationType.ERROR)
      return
    }
    e.coroutineScope.launch(Dispatchers.IO) {
      var shouldRefresh = false
      try {
        val completed = perform(project, root)
        shouldRefresh = completed && refreshAfterSuccess
        if (completed && notifyOnSuccess) {
          notify(project, LoreBundle.message("operation.success", root.presentableUrl), NotificationType.INFORMATION)
        }
      }
      catch (error: Exception) {
        rethrowCancellation(error)
        shouldRefresh = refreshAfterFailure
        notify(project, error.message.orEmpty().ifBlank { error.javaClass.simpleName }, NotificationType.ERROR)
      }
      finally {
        if (shouldRefresh && !project.isDisposed) project.service<LoreRepositoryService>().refresh(root)
      }
    }
  }

  protected open val saveDocumentsBeforeAction: Boolean = true
  protected open val notifyOnSuccess: Boolean = true
  protected open val refreshAfterSuccess: Boolean = true
  protected open val refreshAfterFailure: Boolean = true

  protected abstract suspend fun perform(project: Project, root: VirtualFile): Boolean

  private fun notify(project: Project, @Nls message: String, type: NotificationType) {
    if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("Lore").createNotification(message, type).notify(project)
  }
}

internal class LoreLoginAction(root: VirtualFile? = null) : LoreRepositoryAction(root) {
  override val saveDocumentsBeforeAction: Boolean = false

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = LoreBundle.message("action.Lore.Login.text")
    e.presentation.description = LoreBundle.message("action.Lore.Login.description")
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val settings = project.service<LoreSettings>()
    val serverUrl = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext null
      val dialog = LoreLoginDialog(project, settings.state.lastServerUrl.orEmpty())
      if (dialog.showAndGet()) dialog.serverUrl.trim() else null
    } ?: return false
    project.service<LoreRepositoryService>().withClient(root) {
      login(serverUrl)
    }
    settings.state.lastServerUrl = serverUrl
    return true
  }
}

internal class LoreLogoutAction(root: VirtualFile? = null) : LoreRepositoryAction(root) {
  override val saveDocumentsBeforeAction: Boolean = false

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = LoreBundle.message("action.Lore.Logout.text")
    e.presentation.description = LoreBundle.message("action.Lore.Logout.description")
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val confirmed = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext false
      Messages.showYesNoDialog(project, LoreBundle.message("logout.confirm.message"), LoreBundle.message("action.Lore.Logout.text"),
                               Messages.getQuestionIcon()) == Messages.YES
    }
    if (!confirmed) return false
    project.service<LoreRepositoryService>().withClient(root) { logout() }
    return true
  }
}

internal class LoreSyncAction(private val root: VirtualFile? = null) : LoreRepositoryAction(root) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    val selected = root ?: e.project?.let { loreRoots(it).singleOrNull() }
    val counts = selected?.let { e.project?.service<LoreRepositoryService>()?.cachedSyncCounts(it) }
    e.presentation.text = LoreBundle.message("action.Lore.Sync.count", counts?.incoming?.toString() ?: "?")
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    project.service<LoreRepositoryService>().withClient(root) { sync() }
    return true
  }
}

internal class LorePushAction(private val root: VirtualFile? = null) : LoreRepositoryAction(root) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    val selected = root ?: e.project?.let { loreRoots(it).singleOrNull() }
    val counts = selected?.let { e.project?.service<LoreRepositoryService>()?.cachedSyncCounts(it) }
    e.presentation.text = LoreBundle.message("action.Lore.Push.count", counts?.outgoing?.toString() ?: "?")
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    project.service<LoreRepositoryService>().withClient(root) { push() }
    return true
  }
}

internal class LoreDiscardUnpushedAction : LoreRepositoryAction() {
  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val repositories = project.service<LoreRepositoryService>()
    val branch = repositories.withClient(root) { status(scan = false).branch }
    val confirmed = withContext(Dispatchers.EDT) {
      Messages.showYesNoDialog(project, LoreBundle.message("discard.unpushed.message", branch),
                               LoreBundle.message("discard.unpushed.title"), Messages.getWarningIcon()) == Messages.YES
    }
    if (!confirmed) return false
    repositories.withClient(root) { discardUnpushedCommits() }
    return true
  }
}

internal class LoreResetAllAction(root: VirtualFile? = null) : LoreRepositoryAction(root) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = LoreBundle.message("action.Lore.ResetAll.text")
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val confirmed = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext false
      Messages.showYesNoDialog(project, LoreBundle.message("reset.all.message"),
                               LoreBundle.message("action.Lore.ResetAll.text"), Messages.getWarningIcon()) == Messages.YES
    }
    if (!confirmed) return false
    project.service<LoreRepositoryService>().withClient(root) { resetAll() }
    return true
  }
}

internal class LoreSwitchBranchAction : LoreRepositoryAction() {
  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val repositories = project.service<LoreRepositoryService>()
    val branches = repositories.withClient(root) { branches() }
    if (branches.isEmpty()) return false
    val selected = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext null
      val dialog = LoreChoiceDialog(project, LoreBundle.message("dialog.branch.title"), LoreBundle.message("dialog.branch.label"), branches)
      if (dialog.showAndGet()) dialog.selection else null
    } ?: return false
    repositories.withClient(root) { switchBranch(selected) }
    return true
  }
}

internal class LoreCreateBranchAction(
  root: VirtualFile? = null,
  private val remote: Boolean = false,
) : LoreRepositoryAction(root) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    val key = if (remote) "action.Lore.CreateRemoteBranch.text" else "action.Lore.CreateBranch.text"
    e.presentation.text = LoreBundle.message(key)
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val name = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext null
      val dialog = LoreNewBranchDialog(project, remote)
      if (dialog.showAndGet()) dialog.name.trim() else null
    } ?: return false
    project.service<LoreRepositoryService>().withClient(root) {
      if (remote) createRemoteBranch(name)
      else createBranch(name)
    }
    return true
  }
}

internal class LoreRefreshAction(root: VirtualFile? = null) : LoreRepositoryAction(root) {
  override val notifyOnSuccess: Boolean = false

  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = LoreBundle.message("action.Lore.Refresh.text")
    e.presentation.icon = com.intellij.icons.AllIcons.Actions.Refresh
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean = true
}

internal class LoreMergeBranchAction(
  root: VirtualFile,
  private val source: String,
  private val target: String,
) : LoreRepositoryAction(root) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = LoreBundle.message("action.Lore.MergeBranch.text", target)
    e.presentation.isEnabled = e.presentation.isEnabled && source != target
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val confirmed = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext false
      Messages.showYesNoDialog(project, LoreBundle.message("merge.confirm.message", source, target),
                               LoreBundle.message("merge.confirm.title"), Messages.getQuestionIcon()) == Messages.YES
    }
    if (!confirmed) return false
    project.service<LoreRepositoryService>().withClient(root) { mergeBranch(source, target) }
    return true
  }
}

internal class LoreDeleteBranchAction(root: VirtualFile, private val branch: String) : LoreRepositoryAction(root) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = LoreBundle.message("action.Lore.DeleteBranch.text")
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val confirmed = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext false
      Messages.showYesNoDialog(project, LoreBundle.message("branch.delete.confirm.message", branch),
                               LoreBundle.message("branch.delete.confirm.title"), Messages.getWarningIcon()) == Messages.YES
    }
    if (!confirmed) return false
    project.service<LoreRepositoryService>().withClient(root) { deleteBranch(branch) }
    return true
  }
}

internal class LoreDeleteRemoteBranchAction(root: VirtualFile, private val branch: String) : LoreRepositoryAction(root) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = LoreBundle.message("action.Lore.DeleteRemoteBranch.text")
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val confirmed = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext false
      Messages.showYesNoDialog(project, LoreBundle.message("branch.remote.delete.confirm.message", branch),
                               LoreBundle.message("branch.remote.delete.confirm.title"), Messages.getWarningIcon()) == Messages.YES
    }
    if (!confirmed) return false
    project.service<LoreRepositoryService>().withClient(root) { deleteRemoteBranch(branch) }
    return true
  }
}

internal class LoreArchiveBranchAction(root: VirtualFile, private val branch: String) : LoreRepositoryAction(root) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = LoreBundle.message("action.Lore.ArchiveBranch.text")
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val confirmed = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext false
      Messages.showYesNoDialog(project, LoreBundle.message("branch.archive.confirm.message", branch),
                               LoreBundle.message("branch.archive.confirm.title"), Messages.getWarningIcon()) == Messages.YES
    }
    if (!confirmed) return false
    project.service<LoreRepositoryService>().withClient(root) { archiveBranch(branch) }
    return true
  }
}

internal class LoreAbortMergeAction(root: VirtualFile? = null) : LoreRepositoryAction(root) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = LoreBundle.message("action.Lore.AbortMerge.text")
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean {
    val confirmed = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext false
      Messages.showYesNoDialog(project, LoreBundle.message("merge.abort.message"),
                               LoreBundle.message("action.Lore.AbortMerge.text"), Messages.getWarningIcon()) == Messages.YES
    }
    if (!confirmed) return false
    project.service<LoreRepositoryService>().withClient(root) { abortMerge() }
    return true
  }
}

internal fun loreRoots(project: Project): List<VirtualFile> {
  val manager = ProjectLevelVcsManager.getInstance(project)
  val vcs = manager.findVcsByName(LoreVcs.NAME) ?: return emptyList()
  return manager.getRootsUnderVcs(vcs).toList()
}

private class LoreChoiceDialog(project: Project, @Nls title: String, @Nls private val label: String, private val choices: List<String>) :
  DialogWrapper(project) {
  var selection: String? = choices.firstOrNull()
    private set

  init {
    this.title = title
    init()
  }

  override fun createCenterPanel() = panel {
    row(label) { comboBox(choices).align(AlignX.FILL).bindItem(::selection).focused() }
  }
}

private class LoreNewBranchDialog(project: Project, remote: Boolean) : DialogWrapper(project) {
  var name: String = ""
    private set

  init {
    val key = if (remote) "dialog.branch.new.remote.title" else "dialog.branch.new.title"
    title = LoreBundle.message(key)
    init()
  }

  override fun createCenterPanel() = panel {
    row(LoreBundle.message("dialog.branch.new.label")) {
      textField().columns(30).bindText(::name).focused()
        .validationOnApply { if (it.text.isBlank()) error(LoreBundle.message("dialog.branch.required")) else null }
    }
  }
}

private class LoreLoginDialog(project: Project, initialServerUrl: String) : DialogWrapper(project) {
  var serverUrl: String = initialServerUrl
    private set

  init {
    title = LoreBundle.message("dialog.login.title")
    init()
  }

  override fun createCenterPanel() = panel {
    row(LoreBundle.message("dialog.login.url.label")) {
      textField().columns(36).bindText(::serverUrl).focused()
        .validationOnApply { if (isValidLoreServerUrl(it.text.trim())) null else error(LoreBundle.message("dialog.login.url.invalid")) }
    }
  }
}
