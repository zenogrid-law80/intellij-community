// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.actions

import com.intellij.icons.AllIcons
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.TreeSpeedSearch
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.ThreeStateCheckBox
import lore4idea.LoreBundle
import lore4idea.commands.LoreFolderRules
import lore4idea.commands.LoreFolderSelection
import lore4idea.commands.LoreFolderState
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

internal class LoreViewTree(private val original: String, folders: List<String>, private val load: (String) -> Unit) {
  private val root = CheckedTreeNode("")
  private val nodes = linkedMapOf("" to root)
  private val loaded = mutableSetOf("")
  private val changes = mutableListOf<LoreFolderSelection>()
  private var text = original
  private val tree: CheckboxTreeBase
  val component: JComponent get() = tree

  init {
    val renderer = object : CheckboxTreeBase.CheckboxTreeCellRendererBase(true, false) {
      override fun customizeRenderer(tree: JTree, value: Any, selected: Boolean, expanded: Boolean,
                                     leaf: Boolean, row: Int, hasFocus: Boolean) {
        val path = (value as? CheckedTreeNode)?.userObject as? String
        if (path == null) {
          textRenderer.append((value as? DefaultMutableTreeNode)?.userObject?.toString().orEmpty())
          return
        }
        val state = LoreFolderRules.state(text, path)
        textRenderer.append(if (path.isEmpty()) LoreBundle.message("dialog.view.all.folders") else path.substringAfterLast('/'))
        if (state == LoreFolderState.MIXED) textRenderer.append(LoreBundle.message("dialog.view.partial"))
        textRenderer.icon = AllIcons.Nodes.Folder
        threeStateCheckBox.state = when (state) {
          LoreFolderState.INCLUDED -> ThreeStateCheckBox.State.SELECTED
          LoreFolderState.EXCLUDED -> ThreeStateCheckBox.State.NOT_SELECTED
          LoreFolderState.MIXED -> ThreeStateCheckBox.State.DONT_CARE
        }
      }
    }
    tree = CheckboxTreeBase(renderer, root, CheckboxTreeBase.CheckPolicy(false, false, false, false))
    tree.isRootVisible = true
    tree.visibleRowCount = 18
    tree.preferredSize = JBUI.size(600, 360)
    tree.addCheckboxTreeListener(object : CheckboxTreeListener {
      override fun nodeStateChanged(node: CheckedTreeNode) {
        val path = node.userObject as? String ?: return
        LoreFolderRules.select(changes, LoreFolderSelection(path, node.isChecked))
        updateStates()
      }
    })
    tree.addTreeWillExpandListener(object : TreeWillExpandListener {
      override fun treeWillExpand(event: TreeExpansionEvent) {
        val path = (event.path.lastPathComponent as? CheckedTreeNode)?.userObject as? String ?: return
        if (path !in loaded) load(path)
      }
      override fun treeWillCollapse(event: TreeExpansionEvent) = Unit
    })
    TreeSpeedSearch.installOn(tree, true) { it.lastPathComponent.toString() }
    showFolders("", folders)
    tree.expandRow(0)
    tree.setSelectionRow(0)
  }

  fun selections(): List<LoreFolderSelection> = changes.toList()

  fun selectAll(included: Boolean) {
    LoreFolderRules.select(changes, LoreFolderSelection("", included))
    updateStates()
  }

  fun undoSelections() {
    changes.clear()
    updateStates()
  }

  fun showFolders(parent: String, folders: List<String>) {
    val node = nodes.getValue(parent)
    val path = TreePath(node.path)
    val expanded = tree.isExpanded(path)
    node.removeAllChildren()
    for (path in folders) {
      val child = CheckedTreeNode(path)
      child.add(DefaultMutableTreeNode(LoreBundle.message("dialog.view.expand")))
      nodes[path] = child
      node.add(child)
    }
    loaded.add(parent)
    (tree.model as DefaultTreeModel).nodeStructureChanged(node)
    updateStates()
    if (expanded) tree.expandPath(path)
  }

  fun showLoading(parent: String) = placeholder(parent, LoreBundle.message("dialog.view.loading"))

  fun showFailure(parent: String) = placeholder(parent, LoreBundle.message("dialog.view.retry.hint"))

  private fun placeholder(parent: String, message: String) {
    val node = nodes.getValue(parent)
    node.removeAllChildren()
    node.add(DefaultMutableTreeNode(message))
    (tree.model as DefaultTreeModel).nodeStructureChanged(node)
  }

  private fun updateStates() {
    text = LoreFolderRules.merge(original, changes)
    for ((path, node) in nodes) node.isChecked = LoreFolderRules.state(text, path) == LoreFolderState.INCLUDED
    tree.repaint()
  }
}
