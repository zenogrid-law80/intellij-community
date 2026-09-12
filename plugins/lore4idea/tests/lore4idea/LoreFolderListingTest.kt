// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.google.gson.JsonObject
import com.intellij.openapi.vcs.VcsException
import com.intellij.testFramework.common.timeoutRunBlocking
import lore4idea.commands.LoreClient
import lore4idea.commands.LoreEvent
import lore4idea.commands.LoreExecutor
import lore4idea.commands.parseLoreFolders
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path

@Timeout(30)
internal class LoreFolderListingTest {
  @Test
  fun `nested listing restores paths relative to the requested folder`() {
    val result = parseLoreFolders(Path.of("/repo"), "Project/Source", "abc",
                                 events("Source/", "Source/Sub/", "Source/file.txt"))
    assertEquals(listOf("Project/Source/Sub"), result.folders)
  }

  @Test
  fun `listing rejects a different revision and unexpected child paths`() {
    for (nodes in listOf(events("Other/child/"), events("Source/../"), events("Source/a/b/"), events().dropLast(1))) {
      assertThrows(VcsException::class.java) { parseLoreFolders(Path.of("/repo"), "Source", "abc", nodes) }
    }
    assertThrows(VcsException::class.java) { parseLoreFolders(Path.of("/repo"), "", "different", events()) }
  }

  @Test
  fun `queries pin the revision and bound the dump depth`() = timeoutRunBlocking {
    val calls = mutableListOf<List<String>>()
    val executor = LoreExecutor { _, arguments ->
      calls.add(arguments)
      """{"tagName":"repositoryDumpBegin","data":{"revision":"abc"}}
        |{"tagName":"repositoryDumpEnd","data":{}}
        |{"tagName":"complete","data":{"status":0}}
      """.trimMargin()
    }
    val client = LoreClient(Path.of("/repo"), executor)
    client.viewFolders("", "abc")
    client.viewFolders("Project/Source", "abc")
    assertEquals(listOf("--cache", "repository", "dump", "--revision", "abc", "--max-depth", "1"), calls[0])
    assertEquals(listOf("--cache", "repository", "dump", "--revision", "abc", "--max-depth", "2", "--path=Project/Source"), calls[1])
  }

  private fun events(vararg names: String): List<LoreEvent> =
    listOf(LoreEvent("repositoryDumpBegin", JsonObject().apply { addProperty("revision", "abc") })) +
    names.map { name -> LoreEvent("repositoryStateDumpNode", JsonObject().apply { addProperty("name", name) }) } +
    LoreEvent("repositoryDumpEnd", JsonObject())
}
