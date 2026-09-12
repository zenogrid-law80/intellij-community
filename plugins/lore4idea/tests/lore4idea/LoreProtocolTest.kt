// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.vcs.VcsException
import lore4idea.commands.LoreProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

internal class LoreProtocolTest {
  @Test
  fun `reads JSON events and preserves path characters`() {
    val status = LoreProtocol.status(LoreProtocol.events(statusOutput(file("한 글\nfile.txt", "keep"))))
    assertEquals("abc", status.revision)
    assertEquals("main", status.branch)
    assertEquals("한 글\nfile.txt", status.files.single().path)
    assertEquals("한 글\nfile.txt", status.files.single().beforePath)
    assertFalse(status.files.single().staged)
  }

  @Test
  fun `retains both paths of a staged move`() {
    val status = LoreProtocol.status(LoreProtocol.events(statusOutput(file("new.txt", "move", true, "old.txt"))))
    assertEquals("old.txt", status.files.single().beforePath)
    assertTrue(status.files.single().staged)
  }

  @Test
  fun `a committed merge is not a pending merge`() {
    val committed = statusOutput().replace("\"revisionMerged\":\"0000\"", "\"revisionMerged\":\"merged-parent\"")
    assertFalse(LoreProtocol.status(LoreProtocol.events(committed)).merged)
    val pending = committed.replace("\"revisionStaged\":\"0000\"", "\"revisionStaged\":\"staged\"")
    assertTrue(LoreProtocol.status(LoreProtocol.events(pending)).merged)
  }

  @Test
  fun `rejects incomplete and failed responses`() {
    assertThrows(VcsException::class.java) { LoreProtocol.events("") }
    assertThrows(VcsException::class.java) { LoreProtocol.events("not JSON") }
    val error = assertThrows(VcsException::class.java) {
      LoreProtocol.events("""{"tagName":"complete","data":{"status":-1,"error":{"message":"denied"}}}""")
    }
    assertTrue(error.message!!.contains("denied"))
  }

  @Test
  fun `accepts a relay failure after a local commit`() {
    val output = """
      {"tagName":"revisionCommitRevision","data":{"revision":"abc"}}
      {"tagName":"complete","data":{"status":6,"error":{"message":"Disconnected from server"}}}
    """.trimIndent()
    assertEquals("revisionCommitRevision", LoreProtocol.events(output, "revisionCommitRevision").first().tag)
    assertThrows(VcsException::class.java) { LoreProtocol.events(output) }
  }

  @Test
  fun `rejects paths outside repository and metadata paths`() {
    val root = Path.of("/repo")
    for (path in listOf("", ".", "../file", "/file", "a/../../file", "C:/file", "a\\..\\file", ".lore/config", "a/.urc/state")) {
      assertThrows(VcsException::class.java, { LoreProtocol.resolvePath(root, path) }, path)
    }
    assertEquals(root.resolve("-a b.txt"), LoreProtocol.resolvePath(root, "-a b.txt"))
  }

  @Test
  fun `attaches typed metadata to the correct history revision`() {
    val output = """
      {"tagName":"fileHistory","data":{"revision":"a","path":"new.txt","action":"keep"}}
      {"tagName":"metadata","data":{"key":"message","value":{"tagName":"string","data":"Latest"}}}
      {"tagName":"metadata","data":{"key":"timestamp","value":{"tagName":"numeric","data":1234}}}
      {"tagName":"fileHistory","data":{"revision":"b","path":"old.txt","action":"add"}}
      {"tagName":"metadata","data":{"key":"message","value":{"tagName":"string","data":"Initial"}}}
      $complete
    """.trimIndent()
    val history = LoreProtocol.history(LoreProtocol.events(output))
    assertEquals(listOf("new.txt", "old.txt"), history.map { it.path })
    assertEquals(listOf("Latest", "Initial"), history.map { it.metadata["message"] })
    assertEquals("1234", history.first().metadata["timestamp"])
  }

  @Test
  fun `reads repository log parents metadata and changes`() {
    val output = """
      {"tagName":"revisionHistoryEntry","data":{"revision":"a","parent":["b","0000"]}}
      {"tagName":"metadata","data":{"key":"message","value":{"tagName":"string","data":"Latest"}}}
      {"tagName":"metadata","data":{"key":"branch","value":{"tagName":"context","data":"branch-id"}}}
      {"tagName":"revisionInfoDelta","data":{"path":"dir/file.txt","action":"add"}}
      $complete
    """.trimIndent()
    val entry = LoreProtocol.log(LoreProtocol.events(output)).single()
    assertEquals("a", entry.revision)
    assertEquals(listOf("b"), entry.parents)
    assertEquals("Latest", entry.metadata["message"])
    assertEquals("branch-id", entry.metadata["branch"])
    assertEquals("dir/file.txt", entry.changes.single().path)
  }

  @Test
  fun `reads local branch references`() {
    val output = """
      {"tagName":"branchListEntry","data":{"name":"main","latest":"abc","isCurrent":true}}
      $complete
    """.trimIndent()
    val branch = LoreProtocol.branches(LoreProtocol.events(output)).single()
    assertEquals("main", branch.name)
    assertEquals("abc", branch.latestRevision)
    assertTrue(branch.current)
  }

  @Test
  fun `reads remote tracking status numbers`() {
    val data = com.google.gson.JsonObject().apply {
      addProperty("branchName", "main")
      addProperty("revisionLocal", "local")
      addProperty("revisionLocalNumber", 5)
      addProperty("revisionRemote", "remote")
      addProperty("revisionRemoteNumber", 13)
      addProperty("isLocalAhead", 0)
      addProperty("isRemoteAhead", 1)
      addProperty("remoteAvailable", 1)
      addProperty("remoteAuthorized", 1)
      addProperty("remoteBranchExist", 1)
    }
    val event = com.google.gson.JsonObject().apply {
      addProperty("tagName", "repositoryStatusRevision")
      add("data", data)
    }
    val output = "$event\n$complete"
    val status = LoreProtocol.trackingStatus(LoreProtocol.events(output))
    assertEquals(5L, status.localRevisionNumber)
    assertEquals(13L, status.remoteRevisionNumber)
    assertFalse(status.localAhead)
    assertTrue(status.remoteAhead)
  }

  @Test
  fun `reads resolved user names`() {
    val output = """
      {"tagName":"authUserInfo","data":{"id":"user-id","name":"Test User"}}
      $complete
    """.trimIndent()
    assertEquals(mapOf("user-id" to "Test User"), LoreProtocol.users(LoreProtocol.events(output)))
  }

  companion object {
    const val complete = """{"tagName":"complete","data":{"status":0}}"""

    fun statusOutput(vararg files: String): String =
      """{"tagName":"repositoryStatusRevision","data":{"revision":"abc","branchName":"main","revisionMerged":"0000","revisionStaged":"0000"}}""" +
      "\n" + files.joinToString("\n") + "\n" + complete

    fun file(path: String, action: String, staged: Boolean = false, fromPath: String = ""): String {
      val data = com.google.gson.JsonObject().apply {
        addProperty("path", path)
        addProperty("action", action)
        addProperty("type", "file")
        addProperty("flagStaged", staged)
        addProperty("flagConflictUnresolved", false)
        addProperty("fromPath", fromPath)
      }
      return """{"tagName":"repositoryStatusFile","data":$data}"""
    }
  }
}
