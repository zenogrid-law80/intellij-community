// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import lore4idea.commands.EelLoreExecutor
import lore4idea.commands.LoreFolderRules
import lore4idea.commands.LoreFolderSelection
import lore4idea.commands.LoreFolderState
import lore4idea.commands.LoreClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

@TestApplication
@Timeout(90)
@EnabledIfSystemProperty(named = "lore.executable", matches = ".+")
internal class LoreCliTest {
  @TempDir
  lateinit var root: Path

  @Test
  fun `tracks commits content history rollback and branches`() = timeoutRunBlocking(timeout = 60.seconds) {
    val client = LoreClient(root, EelLoreExecutor(System.getProperty("lore.executable"), 30))
    step("create repository") { client.command("--offline", "repository", "create", "lore://localhost/lore4idea-test") }
    assertTrue(LoreRootChecker.isLoreRoot(root))
    val file = root.resolve("한 글.txt")
    Files.writeString(file, "first\n")
    assertEquals("add", step("read added file status") { client.status() }.files.single { it.nodeType == "file" }.action)
    step("commit file") { client.commit(setOf("한 글.txt"), "Initial") }
    val revision = step("read committed status") { client.status() }.revision
    assertEquals("first\n", step("read revision content") { client.content("한 글.txt", revision) }.toString(Charsets.UTF_8))
    Files.writeString(file, "second\n")
    step("revert file") { client.revert(listOf("한 글.txt")) }
    assertEquals("first\n", Files.readString(file))
    val history = step("read file history") { client.history("한 글.txt", 20) }
    assertEquals("Initial", history.first().metadata["message"])
    val logEntry = step("read repository history") { client.log("main", 20) }.single()
    assertEquals(revision, logEntry.revision)
    assertEquals("Initial", logEntry.metadata["message"])
    val details = step("read revision details") { client.revision(revision) }
    assertEquals("한 글.txt", details.changes.single().path)
    assertEquals("add", details.changes.single().action)
    step("create branch") { client.createBranch("feature") }
    assertEquals("feature", step("read branch after creation") { client.status() }.branch)
    assertEquals(revision, step("read new branch tip") { client.branchDetails() }.single { it.name == "feature" }.latestRevision)
    step("switch to main") { client.command("--offline", "branch", "switch", "--", "main") }
    assertEquals("main", step("read main branch status") { client.status() }.branch)
    step("switch branch") { client.command("--offline", "branch", "switch", "--", "feature") }
    assertEquals("feature", step("read branch status") { client.status() }.branch)
    assertEquals(setOf("feature", "main"), step("read branches") { client.branchDetails() }.map { it.name }.toSet())
    step("switch to main before branch deletion") { client.command("--offline", "branch", "switch", "--", "main") }
    step("delete local feature branch") { client.deleteBranch("feature") }
    assertEquals(listOf("main"), step("read branches after deletion") { client.branches() })
  }

  @Test
  fun `merges a local feature into main`() = timeoutRunBlocking(timeout = 60.seconds) {
    val client = LoreClient(root, EelLoreExecutor(System.getProperty("lore.executable"), 30))
    client.command("--offline", "repository", "create", "lore://localhost/lore4idea-merge-test")
    Files.writeString(root.resolve("base.txt"), "base\n")
    client.commit(setOf("base.txt"), "Initial")
    client.createBranch("feature")
    Files.writeString(root.resolve("feature.txt"), "feature\n")
    client.commit(setOf("feature.txt"), "Feature")
    client.command("--offline", "branch", "switch", "--", "main")
    Files.writeString(root.resolve("main.txt"), "main\n")
    client.commit(setOf("main.txt"), "Main")
    step("merge feature into main") { client.mergeBranch("feature", "main") }
    assertEquals("main", client.status().branch)
    assertTrue(client.status().files.isEmpty())
    assertEquals("feature\n", Files.readString(root.resolve("feature.txt")))
    assertEquals("main\n", Files.readString(root.resolve("main.txt")))
  }

  @Test
  fun `view rules retain excluded files and restore newly included files`() = timeoutRunBlocking(timeout = 60.seconds) {
    val client = LoreClient(root, EelLoreExecutor(System.getProperty("lore.executable"), 30))
    client.command("--offline", "repository", "create", "lore://localhost/lore4idea-view-test")
    Files.writeString(root.resolve("keep.txt"), "keep\n")
    Files.writeString(root.resolve("other.txt"), "other\n")
    client.commit(setOf("keep.txt", "other.txt"), "Initial")
    val revision = client.status().revision
    val rules = "# Only keep.txt\n**\n!keep.txt\n"
    client.saveView(null, rules, apply = true)
    assertEquals(rules, client.view())
    assertTrue(Files.exists(root.resolve("other.txt")))
    Files.delete(root.resolve("other.txt"))
    assertTrue(client.status().files.isEmpty())
    client.saveView(rules, "", apply = false)
    assertFalse(Files.exists(root.resolve("other.txt")))
    client.saveView("", "", apply = true)
    assertEquals("other\n", Files.readString(root.resolve("other.txt")))
    assertEquals(revision, client.status().revision)
    assertFalse(client.status().merged)
    assertTrue(client.status().files.isEmpty())
  }

  @Test
  fun `folder queries include absent paths and selections merge external rules`() = timeoutRunBlocking(timeout = 60.seconds) {
    val client = LoreClient(root, EelLoreExecutor(System.getProperty("lore.executable"), 30))
    client.command("--offline", "repository", "create", "lore://localhost/lore4idea-folders-test")
    val files = setOf("Source/main.kt", "Assets/한 글 [art]/file.txt", "Assets/Other/file.txt")
    for (path in files) {
      val file = root.resolve(path)
      Files.createDirectories(file.parent)
      Files.writeString(file, path)
    }
    client.commit(files, "Initial")
    val revision = client.status().revision
    client.saveView(null, "**\n!/Source/**\n", apply = false)
    Files.delete(root.resolve("Assets/한 글 [art]/file.txt"))
    Files.delete(root.resolve("Assets/한 글 [art]"))
    assertEquals(listOf("Assets", "Source"), client.viewFolders("", revision).folders)
    assertEquals(listOf("Assets/Other", "Assets/한 글 [art]"), client.viewFolders("Assets", revision).folders)
    assertTrue(client.viewFolders("Assets/Other", revision).folders.isEmpty())
    assertFalse(Files.exists(root.resolve("Assets/한 글 [art]")))
    val external = "# external edit\n**\n!/Source/**\n"
    Files.writeString(root.resolve(".lore/view"), external)
    client.saveFolderSelections(revision, listOf(LoreFolderSelection("Assets/한 글 [art]", true)), apply = false)
    assertTrue(client.view().orEmpty().startsWith(external))
    assertFalse(Files.exists(root.resolve("Assets/한 글 [art]")))
    client.saveFolderSelections(revision, emptyList(), apply = true)
    assertEquals("Assets/한 글 [art]/file.txt", Files.readString(root.resolve("Assets/한 글 [art]/file.txt")))
    assertEquals(revision, client.status().revision)
    client.saveFolderSelections(revision, listOf(LoreFolderSelection("", true)), apply = false)
    assertEquals(LoreFolderState.INCLUDED, LoreFolderRules.state(client.view().orEmpty(), "Assets/Other"))
  }

  private suspend fun <T> step(name: String, action: suspend () -> T): T = try {
    action()
  }
  catch (error: Exception) {
    throw AssertionError(name, error)
  }
}
