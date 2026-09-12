// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.vcs.VcsException
import com.intellij.testFramework.common.timeoutRunBlocking
import lore4idea.LoreProtocolTest.Companion.complete
import lore4idea.LoreProtocolTest.Companion.file
import lore4idea.LoreProtocolTest.Companion.statusOutput
import lore4idea.commands.LoreClient
import lore4idea.commands.LoreExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Timeout(30)
internal class LoreViewTest {
  @TempDir
  lateinit var root: Path

  private val viewPath: Path get() = root.resolve(".lore/view")

  @BeforeEach
  fun prepare() {
    Files.createDirectory(root.resolve(".lore"))
  }

  @Test
  fun `save preserves comments order and Unicode without synchronizing`() = timeoutRunBlocking {
    val executor = RecordingExecutor(statusOutput())
    val client = LoreClient(root, executor)
    assertNull(client.view())
    val rules = "# 작업 목록\n**\n!한 글/**\n한 글/cache/**\n"
    client.saveView(null, rules, apply = false)
    assertEquals(rules, client.view())
    assertEquals(listOf(listOf("--offline", "status")), executor.commands)
  }

  @Test
  fun `apply restores missing included files at the current revision`() = timeoutRunBlocking {
    val executor = RecordingExecutor(statusOutput(), statusOutput(file("included.txt", "delete")), complete)
    LoreClient(root, executor).saveView(null, "", apply = true)
    assertEquals("", Files.readString(viewPath))
    assertEquals(listOf("--cache", "sync", "--reset", "abc"), executor.commands.last())
  }

  @Test
  fun `apply rejects dirty files before changing the view`() {
    Files.writeString(viewPath, "old/**\n")
    val executor = RecordingExecutor(statusOutput(file("local.txt", "keep")))
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(root, executor).saveView("old/**\n", "**\n", apply = true) }
    }
    assertEquals("old/**\n", Files.readString(viewPath))
    assertEquals(1, executor.commands.size)
  }

  @Test
  fun `apply preserves staged deletions`() {
    val executor = RecordingExecutor(statusOutput(file("deleted.txt", "delete", staged = true)))
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(root, executor).saveView(null, "", apply = true) }
    }
    assertFalse(Files.exists(viewPath))
    assertEquals(1, executor.commands.size)
  }

  @Test
  fun `apply preserves newly included local changes and restores the old rules`() {
    Files.writeString(viewPath, "hidden/**\n")
    val executor = RecordingExecutor(statusOutput(), statusOutput(file("hidden/local.txt", "keep")))
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(root, executor).saveView("hidden/**\n", "", apply = true) }
    }
    assertEquals("hidden/**\n", Files.readString(viewPath))
    assertTrue(executor.commands.none { "sync" in it })
  }

  @Test
  fun `invalid rules restore a missing view file`() {
    val executor = RecordingExecutor("""{"tagName":"complete","data":{"status":1}}""")
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(root, executor).saveView(null, "[", apply = false) }
    }
    assertFalse(Files.exists(viewPath))
  }

  @Test
  fun `save preserves external edits`() {
    Files.writeString(viewPath, "external/**\n")
    val executor = RecordingExecutor()
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(root, executor).saveView(null, "**\n", apply = true) }
    }
    assertEquals("external/**\n", Files.readString(viewPath))
    assertTrue(executor.commands.isEmpty())
  }

  @Test
  fun `save rejects a stale repository dump before changing rules`() {
    val executor = RecordingExecutor(statusOutput())
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(root, executor).saveView(null, "**\n", apply = false, expectedRevision = "old") }
    }
    assertFalse(Files.exists(viewPath))
    assertEquals(1, executor.commands.size)
  }

  @Test
  fun `apply failure keeps the rules used for partial materialization`() {
    val executor = RecordingExecutor(statusOutput(), statusOutput(), """{"tagName":"complete","data":{"status":1}}""")
    val error = assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(root, executor).saveView(null, "**\n!src/**\n", apply = true) }
    }
    assertEquals("**\n!src/**\n", Files.readString(viewPath))
    assertTrue(error.cause is VcsException)
  }

  private class RecordingExecutor(vararg responses: String) : LoreExecutor {
    private val responses = ArrayDeque(responses.toList())
    val commands = mutableListOf<List<String>>()

    override suspend fun execute(root: Path, arguments: List<String>): String {
      commands.add(arguments)
      return responses.removeFirst()
    }
  }
}
