// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import lore4idea.commands.LoreFolderRules
import lore4idea.commands.LoreFolderSelection
import lore4idea.commands.LoreFolderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class LoreFolderRulesTest {
  @Test
  fun `custom rules stay mixed until a subtree override`() {
    val original = "# custom\r\n*.tmp\r\n"
    assertEquals(LoreFolderState.MIXED, LoreFolderRules.state(original, "Source"))
    val selected = LoreFolderRules.merge(original, listOf(LoreFolderSelection("Source", true)))
    assertTrue(selected.startsWith(original))
    assertEquals(LoreFolderState.INCLUDED, LoreFolderRules.state(selected, "Source/Sub"))
    assertEquals(LoreFolderState.MIXED, LoreFolderRules.state(selected, "Other"))
  }

  @Test
  fun `parent and root selections replace earlier descendants`() {
    val changes = mutableListOf<LoreFolderSelection>()
    LoreFolderRules.select(changes, LoreFolderSelection("Source/Sub", true))
    LoreFolderRules.select(changes, LoreFolderSelection("source", false))
    assertEquals(listOf(LoreFolderSelection("source", false)), changes)
    LoreFolderRules.select(changes, LoreFolderSelection("", true))
    assertEquals(listOf(LoreFolderSelection("", true)), changes)
    assertEquals(LoreFolderState.INCLUDED, LoreFolderRules.state(LoreFolderRules.merge("**\n", changes), "future"))
  }

  @Test
  fun `repeated saves compact managed rules and accept LoreLens blocks`() {
    var text = "# LoreLens: folder selections\n!/**\n"
    repeat(6) { text = LoreFolderRules.merge(text, listOf(LoreFolderSelection("Assets", it % 2 == 0))) }
    assertEquals("# Lore: folder selections\n!/**\n/Assets/\n/Assets/**\n", text)
    val same = LoreFolderRules.merge(text, listOf(LoreFolderSelection("Assets", false)))
    assertEquals(text, same)
  }

  @Test
  fun `authored rules are ordering barriers`() {
    val before = "# LoreLens: folder selections\n/Assets/\n/Assets/**\n# hand edit\n!/Assets/keep.txt\n"
    val merged = LoreFolderRules.merge(before, listOf(LoreFolderSelection("Assets/Sub", true)))
    assertTrue(merged.contains("/Assets/**\n# hand edit\n!/Assets/keep.txt\n# Lore: folder selections\n"))
    val unknown = "# LoreLens: folder selections\n/Assets/*\n# custom\n"
    assertTrue(LoreFolderRules.merge(unknown, listOf(LoreFolderSelection("Other", true))).startsWith(unknown))
  }

  @Test
  fun `escaped folders and unchanged text survive saving`() {
    val original = "# comment\r\n**\r\n"
    assertEquals(original, LoreFolderRules.merge(original, emptyList()))
    val text = LoreFolderRules.merge(original, listOf(LoreFolderSelection("한 글 [art]", true)))
    assertEquals(LoreFolderState.INCLUDED, LoreFolderRules.state(text, "한 글 [art]/child"))
    assertEquals(LoreFolderState.EXCLUDED, LoreFolderRules.state(text, "한 글 a/child"))
  }
}
