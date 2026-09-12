// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.actions

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.LabelPosition
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lore4idea.LoreBundle
import lore4idea.LoreRepositoryService
import lore4idea.commands.LoreFolderListing
import lore4idea.commands.LoreFolderSelection

internal class LoreViewAction(root: VirtualFile? = null) : LoreRepositoryAction(root) {
  override fun update(e: AnActionEvent) {
    super.update(e)
    e.presentation.text = LoreBundle.message("action.Lore.View.text")
    e.presentation.description = LoreBundle.message("action.Lore.View.description")
  }

  override suspend fun perform(project: Project, root: VirtualFile): Boolean = coroutineScope {
    val repositories = project.service<LoreRepositoryService>()
    val (original, listing) = withBackgroundProgress(project, LoreBundle.message("dialog.view.loading")) {
      repositories.withClient(root) {
        view().orEmpty() to viewFolders("", status(scan = false).revision)
      }
    }
    val owner = this
    val selection = withContext(Dispatchers.EDT) {
      if (project.isDisposed) return@withContext null
      val scope = owner.childScope("Lore view editor")
      lateinit var dialog: LoreViewDialog
      dialog = LoreViewDialog(project, original, listing) { parent ->
        scope.launch(Dispatchers.EDT + ModalityState.current().asContextElement()) {
          try {
            val children = withContext(Dispatchers.IO) {
              repositories.withClient(root) { viewFolders(parent, listing.revision) }
            }
            if (!dialog.isDisposed) dialog.loaded(parent, children.folders)
          }
          catch (error: Exception) {
            rethrowControlFlowException(error)
            if (!dialog.isDisposed) dialog.failed(parent, error.message.orEmpty())
          }
        }
      }
      Disposer.register(dialog.disposable) { scope.cancel() }
      if (dialog.showAndGet()) dialog.selections() to dialog.applyView else null
    } ?: return@coroutineScope false
    repositories.withClient(root) { saveFolderSelections(listing.revision, selection.first, selection.second) }
    true
  }
}

internal class LoreViewDialog(
  project: Project,
  original: String,
  listing: LoreFolderListing,
  private val load: (String) -> Unit,
) : DialogWrapper(project) {
  private val tree = LoreViewTree(original, listing.folders, ::request)
  private val loading = mutableSetOf<String>()
  private val failures = linkedMapOf<String, String>()
  var applyView: Boolean = false
    private set

  init {
    title = LoreBundle.message("dialog.view.title")
    setOKButtonText(LoreBundle.message("dialog.view.save"))
    init()
  }

  fun selections(): List<LoreFolderSelection> = tree.selections()

  private fun request(parent: String) {
    if (!loading.add(parent)) return
    failures.remove(parent)
    tree.showLoading(parent)
    isOKActionEnabled = false
    setErrorText(null)
    load(parent)
  }

  fun loaded(parent: String, folders: List<String>) {
    loading.remove(parent)
    tree.showFolders(parent, folders)
    isOKActionEnabled = loading.isEmpty()
    setErrorText(failures.values.firstOrNull())
  }

  fun failed(parent: String, message: String) {
    loading.remove(parent)
    failures[parent] = message
    tree.showFailure(parent)
    setErrorText(message)
    isOKActionEnabled = loading.isEmpty()
  }

  override fun createCenterPanel() = panel {
    row {
      button(LoreBundle.message("dialog.view.select.all")) { tree.selectAll(true) }
      button(LoreBundle.message("dialog.view.select.none")) { tree.selectAll(false) }
      button(LoreBundle.message("dialog.view.undo")) { tree.undoSelections() }
      button(LoreBundle.message("dialog.view.retry")) { failures.keys.toList().forEach(::request) }
    }
    row {
      scrollCell(tree.component).align(Align.FILL).focused()
        .label(LoreBundle.message("dialog.view.paths"), LabelPosition.TOP)
    }.resizableRow()
    row { text(LoreBundle.message("dialog.view.selection.description")) }
    row { checkBox(LoreBundle.message("dialog.view.apply")).bindSelected(::applyView) }
    row { comment(LoreBundle.message("dialog.view.apply.description")) }
  }
}
