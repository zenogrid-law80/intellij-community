// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.application.EDT
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckedTreeNode
import com.intellij.util.ui.ThreeStateCheckBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lore4idea.actions.LoreViewTree
import lore4idea.commands.LoreFolderSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.awt.event.KeyEvent
import javax.swing.tree.TreePath

@TestApplication
@Timeout(30)
internal class LoreViewTreeTest {
  @Test
  fun `expansion loads children and selection applies to unloaded descendants`() = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val requested = mutableListOf<String>()
      lateinit var view: LoreViewTree
      view = LoreViewTree("*.tmp\n", listOf("src")) {
        requested.add(it)
        view.showLoading(it)
      }
      val tree = view.component as CheckboxTreeBase
      val folder = (tree.model.root as CheckedTreeNode).getChildAt(0) as CheckedTreeNode
      assertTrue(requested.isEmpty())
      val renderer = tree.cellRenderer as CheckboxTreeBase.CheckboxTreeCellRendererBase
      renderer.getTreeCellRendererComponent(tree, folder, true, false, false, 1, true)
      assertEquals(ThreeStateCheckBox.State.DONT_CARE, renderer.threeStateCheckBox.state)
      tree.expandPath(TreePath(folder.path))
      assertEquals(listOf("src"), requested)
      tree.selectionPath = TreePath(folder.path)
      for (listener in tree.keyListeners) {
        listener.keyPressed(KeyEvent(tree, KeyEvent.KEY_PRESSED, 0, 0, KeyEvent.VK_SPACE, ' '))
      }
      assertEquals(listOf(LoreFolderSelection("src", true)), view.selections())
      view.showFolders("src", listOf("src/nested"))
      assertTrue((folder.getChildAt(0) as CheckedTreeNode).isChecked)
      assertTrue(tree.isExpanded(TreePath(folder.path)))
      tree.collapsePath(TreePath(folder.path))
      tree.expandPath(TreePath(folder.path))
      assertEquals(listOf("src"), requested)
      view.selectAll(false)
      assertEquals(listOf(LoreFolderSelection("", false)), view.selections())
      view.undoSelections()
      assertTrue(view.selections().isEmpty())
      renderer.getTreeCellRendererComponent(tree, folder, true, false, false, 1, true)
      assertEquals(ThreeStateCheckBox.State.DONT_CARE, renderer.threeStateCheckBox.state)
    }
  }
}
