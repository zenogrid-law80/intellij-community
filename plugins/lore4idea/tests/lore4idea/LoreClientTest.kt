// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.CommitSession
import com.intellij.testFramework.common.timeoutRunBlocking
import lore4idea.LoreProtocolTest.Companion.complete
import lore4idea.LoreProtocolTest.Companion.file
import lore4idea.LoreProtocolTest.Companion.statusOutput
import lore4idea.commands.LoreClient
import lore4idea.commands.LoreExecutor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path

@Timeout(30)
internal class LoreClientTest {
  @Test
  fun `commit excludes unrelated staged files before mutation`() {
    val executor = RecordingExecutor(statusOutput(file("other.txt", "keep", true)))
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).commit(setOf("selected.txt"), "message") }
    }
    assertEquals(listOf(listOf("--offline", "status", "--scan")), executor.commands)
  }

  @Test
  fun `commit checks staging again before commit`() {
    val executor = RecordingExecutor(statusOutput(), complete, statusOutput(file("other.txt", "add", true)))
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).commit(setOf("selected.txt"), "message") }
    }
    assertTrue(executor.commands.none { "commit" in it })
  }

  @Test
  fun `commit passes message and paths as separate arguments`() = timeoutRunBlocking {
    val executor = RecordingExecutor(statusOutput(), complete, statusOutput(file("-한 글.txt", "add", true)), complete)
    LoreClient(Path.of("/repo"), executor).commit(setOf("-한 글.txt"), "--message\nsecond line")
    assertEquals(listOf("--offline", "stage", "--", "./-한 글.txt"), executor.commands[1])
    assertEquals(listOf("--offline", "commit", "--", "--message\nsecond line"), executor.commands.last())
  }

  @Test
  fun `sync preserves dirty files`() {
    val executor = RecordingExecutor(statusOutput(file("local.txt", "keep")))
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).sync() }
    }
    assertEquals(1, executor.commands.size)
  }

  @Test
  fun `revert unstages and purges only selected paths`() = timeoutRunBlocking {
    val executor = RecordingExecutor(complete, complete)
    LoreClient(Path.of("/repo"), executor).revert(listOf("one.txt", "two.txt"))
    assertEquals(listOf("--offline", "unstage", "--", "./one.txt", "./two.txt"), executor.commands[0])
    assertEquals(listOf("--offline", "reset", "--purge", "--", "./one.txt", "./two.txt"), executor.commands[1])
  }

  @Test
  fun `reset all unstages and purges every dirty path`() = timeoutRunBlocking {
    val executor = RecordingExecutor(statusOutput(file("modified.txt", "keep", true), file("new.txt", "add")), complete, complete)
    LoreClient(Path.of("/repo"), executor).resetAll()
    assertEquals(
      listOf(
        listOf("--offline", "status", "--scan"),
        listOf("--offline", "unstage", "--", "./modified.txt", "./new.txt"),
        listOf("--offline", "reset", "--purge", "--", "./modified.txt", "./new.txt"),
      ), executor.commands
    )
  }

  @Test
  fun `reset all skips the repository root marker`() = timeoutRunBlocking {
    val root = file(".", "keep").replace("\"type\":\"file\"", "\"type\":\"directory\"")
    val executor = RecordingExecutor(statusOutput(root))
    LoreClient(Path.of("/repo"), executor).resetAll()
    assertEquals(listOf(listOf("--offline", "status", "--scan")), executor.commands)
  }

  @Test
  fun `commit and push executor marks the commit context`() {
    val context = CommitContext()
    assertEquals(CommitSession.VCS_COMMIT, LoreCommitAndPushExecutor().createCommitSession(context))
    assertTrue(context.isLorePushAfterCommit)
  }

  @Test
  fun `discard unpushed commits resets to the remote revision`() = timeoutRunBlocking {
    val executor = RecordingExecutor(statusOutput(), remoteBranch("main", "remote-revision"),
                                     history("abc" to "remote-revision", "remote-revision" to "0000"), complete, complete)
    LoreClient(Path.of("/repo"), executor).discardUnpushedCommits()
    assertEquals(listOf("revision", "sync", "--reset", "remote-revision"), executor.commands[3])
    assertEquals(listOf("--offline", "branch", "reset", "remote-revision", "--branch", "main"), executor.commands[4])
  }

  @Test
  fun `discard unpushed commits requires the current remote branch`() {
    val executor = RecordingExecutor(statusOutput(), complete)
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).discardUnpushedCommits() }
    }
    assertEquals(2, executor.commands.size)
  }

  @Test
  fun `discard unpushed commits rejects divergent history`() {
    val executor = RecordingExecutor(
      statusOutput(), remoteBranch("main", "remote-revision"), history("abc" to "other", "other" to "0000")
    )
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).discardUnpushedCommits() }
    }
    assertTrue(executor.commands.none { "reset" in it })
  }

  @Test
  fun `content lookup permits remote hydration and caches the result`() {
    val arguments = LoreClient(Path.of("/repo"), RecordingExecutor()).contentArguments("dir/file.txt", "revision", "/tmp/output")
    assertEquals(listOf("--cache", "file", "write", "--path", "./dir/file.txt", "--revision", "revision", "--output", "/tmp/output"),
                 arguments)
  }

  @Test
  fun `resolves commit user ids in one command`() = timeoutRunBlocking {
    val executor = RecordingExecutor(user("first", "First User") + "\n" + user("second", "Second User") + "\n" + complete)
    val users = LoreClient(Path.of("/repo"), executor).users(listOf("first", "second"))
    assertEquals(mapOf("first" to "First User", "second" to "Second User"), users)
    assertEquals(listOf("auth", "info", "--", "first", "second"), executor.commands.single())
  }

  @Test
  fun `accepts the root directory status but rejects a root file`(): Unit = timeoutRunBlocking {
    val directory = file(".", "keep", true).replace("\"type\":\"file\"", "\"type\":\"directory\"")
    val client = LoreClient(Path.of("/repo"), RecordingExecutor(statusOutput(directory)))
    assertEquals(".", client.status().files.single().path)
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), RecordingExecutor(statusOutput(file(".", "keep")))).status() }
    }
  }

  @Test
  fun `rejects a staged layer before mutation`() {
    val layer = file("layer", "keep", true).replace("\"type\":\"file\"", "\"type\":\"layer\"")
    val executor = RecordingExecutor(statusOutput(layer))
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).commit(setOf("layer"), "message") }
    }
    assertEquals(1, executor.commands.size)
  }

  private class RecordingExecutor(vararg responses: String) : LoreExecutor {
    private val responses = ArrayDeque(responses.toList())
    val commands = mutableListOf<List<String>>()
    override suspend fun execute(root: Path, arguments: List<String>): String {
      commands.add(arguments)
      return responses.removeFirst()
    }
  }

  @Test
  fun `counts both sides of divergent histories`() = timeoutRunBlocking {
    val executor = RecordingExecutor(
      trackingStatus("local", 2, "remote", 3, localAhead = true, remoteAhead = true),
      history("local" to "base", "base" to "0000"),
      history("remote" to "other", "other" to "base", "base" to "0000"),
    )
    val counts = LoreClient(Path.of("/repo"), executor).syncCounts()
    assertEquals(2, counts.incoming)
    assertEquals(1, counts.outgoing)
    assertEquals("main", counts.branch)
    assertEquals(listOf("--remote", "history", "--branch", "main", "10001"), executor.commands.last())
  }

  @Test
  fun `equal tips need no history queries`() = timeoutRunBlocking {
    val executor = RecordingExecutor(trackingStatus("same", 9, "same", 9))
    val counts = LoreClient(Path.of("/repo"), executor).syncCounts()
    assertEquals(0, counts.incoming)
    assertEquals(0, counts.outgoing)
    assertEquals(listOf("status", "--revision-only"), executor.commands.single())
  }

  @Test
  fun `one outgoing commit does not require remote history`() = timeoutRunBlocking {
    val executor = RecordingExecutor(trackingStatus("local", 2, "base", 1, localAhead = true))
    val counts = LoreClient(Path.of("/repo"), executor).syncCounts()
    assertEquals(0, counts.incoming)
    assertEquals(1, counts.outgoing)
    assertEquals(1, executor.commands.size)
  }

  @Test
  fun `a new remote branch needs all local commits`() = timeoutRunBlocking {
    val executor = RecordingExecutor(trackingStatus("local", 2, "0000", 0, localAhead = true, remoteBranchExists = false),
                                     history("local" to "base", "base" to "0000"))
    val counts = LoreClient(Path.of("/repo"), executor).syncCounts()
    assertEquals(0, counts.incoming)
    assertEquals(2, counts.outgoing)
  }

  @Test
  fun `eight incoming commits use the remote status count`() = timeoutRunBlocking {
    val executor = RecordingExecutor(trackingStatus("local", 5, "remote", 13, remoteAhead = true))
    val counts = LoreClient(Path.of("/repo"), executor).syncCounts()
    assertEquals(8, counts.incoming)
    assertEquals(0, counts.outgoing)
    assertEquals(listOf("status", "--revision-only"), executor.commands.single())
  }

  @Test
  fun `incomplete history does not produce a count`() {
    val executor = RecordingExecutor(trackingStatus("local", 2, "remote", 3, localAhead = true, remoteAhead = true),
                                     history("local" to "missing"))
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).syncCounts() }
    }
  }

  @Test
  fun `remote failure is not reported as zero incoming commits`() {
    val failure = """{"tagName":"complete","data":{"status":6,"error":{"message":"Disconnected"}}}"""
    val executor = RecordingExecutor(failure)
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).syncCounts() }
    }
  }

  private fun trackingStatus(
    localRevision: String,
    localNumber: Long,
    remoteRevision: String,
    remoteNumber: Long,
    localAhead: Boolean = false,
    remoteAhead: Boolean = false,
    remoteBranchExists: Boolean = true,
  ): String {
    val data = com.google.gson.JsonObject().apply {
      addProperty("branchName", "main")
      addProperty("revisionLocal", localRevision)
      addProperty("revisionLocalNumber", localNumber)
      addProperty("revisionRemote", remoteRevision)
      addProperty("revisionRemoteNumber", remoteNumber)
      addProperty("isLocalAhead", localAhead.asFlag())
      addProperty("isRemoteAhead", remoteAhead.asFlag())
      addProperty("remoteAvailable", 1)
      addProperty("remoteAuthorized", 1)
      addProperty("remoteBranchExist", remoteBranchExists.asFlag())
    }
    val event = com.google.gson.JsonObject().apply {
      addProperty("tagName", "repositoryStatusRevision")
      add("data", data)
    }
    return "$event\n$complete"
  }

  private fun Boolean.asFlag(): Int = if (this) 1 else 0

  private fun history(vararg revisions: Pair<String, String>): String = revisions.joinToString("\n") { (revision, parent) ->
    """{"tagName":"revisionHistoryEntry","data":{"revision":"$revision","parent":["$parent"]}}"""
  } + "\n" + complete

  @Test
  fun `create branch rejects an existing local name without mutation`() {
    val executor = RecordingExecutor(statusOutput(), remoteBranch("main", "tip"))
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).createBranch("main") }
    }
    assertTrue(executor.commands.none { "create" in it })
  }

  @Test
  fun `create branch rejects a blank name without commands`() {
    val executor = RecordingExecutor()
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).createBranch(" ") }
    }
    assertTrue(executor.commands.isEmpty())
  }

  @Test
  fun `creates a local branch with one create command`() = timeoutRunBlocking {
    val executor = RecordingExecutor(statusOutput(), remoteBranch("main", "tip"), complete)
    LoreClient(Path.of("/repo"), executor).createBranch("feature")
    assertEquals(listOf("--offline", "branch", "create", "--", "feature"), executor.commands.last())
    assertEquals(3, executor.commands.size)
  }

  @Test
  fun `delegates browser login to Lore with the server URL as a direct argument`() = timeoutRunBlocking {
    val executor = RecordingExecutor(complete)
    LoreClient(Path.of("/repo"), executor).login("lores://server.example:41337")
    assertEquals(listOf("login", "lores://server.example:41337"), executor.commands.single())
  }

  @Test
  fun `logs out by clearing Lore authentication`() = timeoutRunBlocking {
    val executor = RecordingExecutor(complete)
    LoreClient(Path.of("/repo"), executor).logout()
    assertEquals(listOf("auth", "clear"), executor.commands.single())
  }

  @Test
  fun `rejects an invalid login URL without running Lore`() {
    val executor = RecordingExecutor()
    assertThrows(VcsException::class.java) {
      timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).login("server.example") }
    }
    assertEquals(emptyList<List<String>>(), executor.commands)
  }

  @Test
  fun `creates and pushes a remote branch`() = timeoutRunBlocking {
    val executor = RecordingExecutor(statusOutput(), remoteBranch("main", "tip"), complete, complete)
    LoreClient(Path.of("/repo"), executor).createRemoteBranch("feature")
    assertEquals(listOf("--offline", "branch", "create", "--", "feature"), executor.commands[2])
    assertEquals(listOf("push", "--", "feature"), executor.commands[3])
  }

  @Test
  fun `deletes a local branch and keeps the remote branch`() = timeoutRunBlocking {
    val executor = RecordingExecutor(statusOutput(), complete)
    LoreClient(Path.of("/repo"), executor).deleteBranch("feature")
    assertEquals(listOf("--offline", "branch", "archive", "--local", "--", "feature"), executor.commands.last())
  }

  @Test
  fun `deletes a remote branch and keeps the local branch`() = timeoutRunBlocking {
    val executor = RecordingExecutor(complete)
    LoreClient(Path.of("/repo"), executor).deleteRemoteBranch("feature")
    assertEquals(listOf("branch", "archive", "--remote", "--", "feature"), executor.commands.single())
  }

  @Test
  fun `archives a branch locally and remotely`() = timeoutRunBlocking {
    val executor = RecordingExecutor(statusOutput(), complete)
    LoreClient(Path.of("/repo"), executor).archiveBranch("feature")
    assertEquals(listOf("branch", "archive", "--", "feature"), executor.commands.last())
  }

  @Test
  fun `does not remove the current branch`() {
    for (remove in listOf<suspend LoreClient.() -> Unit>({ deleteBranch("main") }, { archiveBranch("main") })) {
      val executor = RecordingExecutor(statusOutput())
      assertThrows(VcsException::class.java) {
        timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).remove() }
      }
      assertEquals(listOf(listOf("--offline", "status")), executor.commands)
    }
  }

  private fun remoteBranch(name: String, latest: String): String =
    """{"tagName":"branchListEntry","data":{"name":"$name","latest":"$latest","isCurrent":false}}""" + "\n" + complete

  @Test
  fun `merges the selected branch into the current branch without pushing`() = timeoutRunBlocking {
    val executor = RecordingExecutor(statusOutput(), complete, statusOutput())
    LoreClient(Path.of("/repo"), executor).mergeBranch("feature", "main")
    assertEquals(listOf("--offline", "branch", "merge", "--message",
                        LoreBundle.message("merge.commit.message", "feature", "main"), "--", "feature"), executor.commands[1])
    assertEquals(3, executor.commands.size)
  }

  @Test
  fun `merge preserves dirty files and checks the target branch`() {
    for (response in listOf(statusOutput(file("local.txt", "keep")), statusOutput().replace("main", "other"))) {
      val executor = RecordingExecutor(response)
      assertThrows(VcsException::class.java) {
        timeoutRunBlocking { LoreClient(Path.of("/repo"), executor).mergeBranch("feature", "main") }
      }
      assertEquals(1, executor.commands.size)
    }
  }

  @Test
  fun `pending merge is reported and can be aborted`() = timeoutRunBlocking {
    val merged = statusOutput().replace("0000", "abcd")
    val executor = RecordingExecutor(statusOutput(), complete, merged, merged, complete)
    val client = LoreClient(Path.of("/repo"), executor)
    assertThrows(VcsException::class.java) { timeoutRunBlocking { client.mergeBranch("feature", "main") } }
    client.abortMerge()
    assertEquals(listOf("--offline", "branch", "merge", "abort"), executor.commands.last())
  }

  private fun user(id: String, name: String): String =
    """{"tagName":"authUserInfo","data":{"id":"$id","name":"$name"}}"""
}
