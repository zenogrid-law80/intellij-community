// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.commands

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.vcs.VcsException
import lore4idea.LoreBundle
import lore4idea.rethrowCancellation
import java.nio.file.Path

internal data class LoreEvent(val tag: String, val data: JsonObject)

internal data class LoreFileStatus(
  val path: String,
  val action: String,
  val staged: Boolean,
  val conflict: Boolean,
  val nodeType: String,
  val fromPath: String,
) {
  val beforePath: String? get() = when (action) {
    "add", "copy" -> null
    "move" -> fromPath
    else -> path
  }
}

internal data class LoreStatus(val revision: String, val branch: String, val merged: Boolean, val files: List<LoreFileStatus>)

internal data class LoreTrackingStatus(
  val branch: String,
  val localRevision: String,
  val localRevisionNumber: Long,
  val remoteRevision: String,
  val remoteRevisionNumber: Long,
  val localAhead: Boolean,
  val remoteAhead: Boolean,
  val remoteAvailable: Boolean,
  val remoteAuthorized: Boolean,
  val remoteBranchExists: Boolean,
)

internal data class LoreHistoryEntry(
  val revision: String,
  val path: String,
  val deleted: Boolean,
  val metadata: MutableMap<String, String> = linkedMapOf(),
)

internal data class LoreBranch(
  val name: String,
  val latestRevision: String,
  val current: Boolean,
)

internal data class LoreLogEntry(
  val revision: String,
  val parents: List<String>,
  val metadata: MutableMap<String, String> = linkedMapOf(),
  val changes: MutableList<LoreLogChange> = mutableListOf(),
)

internal data class LoreLogResponse(val entries: List<LoreLogEntry>, val userNames: Map<String, String>)

internal data class LoreLogChange(val path: String, val action: String)

internal data class LoreSyncCounts(val branch: String, val incoming: Int, val outgoing: Int)

internal object LoreProtocol {
  fun events(output: String, acceptFailureAfter: String? = null): List<LoreEvent> {
    try {
      val events = output.lineSequence().filter { it.isNotBlank() }.map { line ->
        val json = JsonParser.parseString(line).asJsonObject
        LoreEvent(json.requiredString("tagName"), json.getAsJsonObject("data"))
      }.toList()
      val complete = events.lastOrNull { it.tag == "complete" } ?: throw VcsException(LoreBundle.message("error.incomplete"))
      if (complete.data.get("status").asInt != 0 && events.none { it.tag == acceptFailureAfter }) {
        val message = complete.data.getAsJsonObject("error")?.get("message")?.asString
                      ?: events.lastOrNull { it.tag == "error" }?.data?.get("errorInner")?.asString
                      ?: complete.data.toString()
        throw VcsException(LoreBundle.message("error.command", message))
      }
      return events
    }
    catch (e: RuntimeException) {
      rethrowCancellation(e)
      throw VcsException(LoreBundle.message("error.protocol", e.message.orEmpty()), e)
    }
  }

  fun status(events: List<LoreEvent>): LoreStatus {
    val revision = events.singleOrNull { it.tag == "repositoryStatusRevision" }?.data
                   ?: throw VcsException(LoreBundle.message("error.protocol", "repositoryStatusRevision"))
    val files = events.filter { it.tag == "repositoryStatusFile" }.map { event ->
      val data = event.data
      val action = data.requiredString("action")
      if (action !in setOf("keep", "add", "delete", "move", "copy")) {
        throw VcsException(LoreBundle.message("error.protocol", action))
      }
      LoreFileStatus(data.requiredString("path"), action, data.flag("flagStaged"), data.flag("flagConflictUnresolved"),
                     data.requiredString("type"), data.requiredString("fromPath"))
    }
    return LoreStatus(revision.requiredString("revision"), revision.requiredString("branchName"),
                      revision.requiredString("revisionMerged").any { it != '0' } &&
                      revision.requiredString("revisionStaged").any { it != '0' }, files)
  }

  fun trackingStatus(events: List<LoreEvent>): LoreTrackingStatus {
    val revision = events.singleOrNull { it.tag == "repositoryStatusRevision" }?.data
                   ?: throw VcsException(LoreBundle.message("error.protocol", "repositoryStatusRevision"))
    return LoreTrackingStatus(
      revision.requiredString("branchName"),
      revision.requiredString("revisionLocal"),
      revision.requiredLong("revisionLocalNumber"),
      revision.requiredString("revisionRemote"),
      revision.requiredLong("revisionRemoteNumber"),
      revision.numericFlag("isLocalAhead"),
      revision.numericFlag("isRemoteAhead"),
      revision.numericFlag("remoteAvailable"),
      revision.numericFlag("remoteAuthorized"),
      revision.numericFlag("remoteBranchExist"),
    )
  }

  fun history(events: List<LoreEvent>): List<LoreHistoryEntry> {
    val entries = mutableListOf<LoreHistoryEntry>()
    for (event in events) {
      when (event.tag) {
        "fileHistory" -> entries.add(LoreHistoryEntry(event.data.requiredString("revision"), event.data.requiredString("path"),
                                                    event.data.requiredString("action") == "delete"))
        "metadata" -> {
          val entry = entries.lastOrNull() ?: continue
          val value = event.data.getAsJsonObject("value")
          if (value?.get("tagName")?.asString in setOf("string", "numeric")) {
            entry.metadata.putIfAbsent(event.data.requiredString("key"), value.get("data").asString)
          }
        }
      }
    }
    return entries
  }

  fun branches(events: List<LoreEvent>): List<LoreBranch> = events.asSequence()
    .filter { it.tag == "branchListEntry" }
    .map { LoreBranch(it.data.requiredString("name"), it.data.requiredString("latest"), it.data.flag("isCurrent")) }
    .distinctBy { it.name }
    .sortedBy { it.name }
    .toList()

  fun users(events: List<LoreEvent>): Map<String, String> = events.asSequence()
    .filter { it.tag == "authUserInfo" }
    .associate { it.data.requiredString("id") to it.data.requiredString("name") }

  fun log(events: List<LoreEvent>): List<LoreLogEntry> {
    val entries = mutableListOf<LoreLogEntry>()
    for (event in events) {
      when (event.tag) {
        "revisionHistoryEntry", "revisionInfo" -> {
          val parents = event.data.getAsJsonArray("parent")?.map { it.asString }
                        ?.filterNot { it.all { character -> character == '0' } }.orEmpty()
          entries.add(LoreLogEntry(event.data.requiredString("revision"), parents))
        }
        "metadata" -> addMetadata(entries.lastOrNull(), event.data)
        "revisionInfoDelta" -> entries.lastOrNull()?.changes?.add(
          LoreLogChange(event.data.requiredString("path"), event.data.requiredString("action"))
        )
      }
    }
    return entries
  }

  fun logResponse(events: List<LoreEvent>): LoreLogResponse = LoreLogResponse(log(events), users(events))

  private fun addMetadata(entry: LoreLogEntry?, data: JsonObject) {
    if (entry == null) return
    val value = data.getAsJsonObject("value") ?: return
    if (value.get("tagName")?.asString in setOf("string", "numeric", "context")) {
      entry.metadata.putIfAbsent(data.requiredString("key"), value.get("data").asString)
    }
  }

  fun resolvePath(root: Path, path: String): Path {
    val base = root.toAbsolutePath().normalize()
    val parts = path.split('/')
    if (path.isEmpty() || path.startsWith('/') || path.contains('\\') || path.contains('\u0000') ||
        parts.any { it.isEmpty() || it == "." || it == ".." || it.contains(':') || it.equals(".lore", true) || it.equals(".urc", true) }) {
      throw VcsException(LoreBundle.message("error.path", path))
    }
    val result = base.resolve(path).normalize()
    if (!result.startsWith(base) || result == base) throw VcsException(LoreBundle.message("error.path", path))
    return result
  }
}

internal fun JsonObject.requiredString(name: String): String {
  val value = get(name)
  if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
    throw VcsException(LoreBundle.message("error.protocol", name))
  }
  return value.asString
}

private fun JsonObject.requiredLong(name: String): Long {
  val value = get(name)
  if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) {
    throw VcsException(LoreBundle.message("error.protocol", name))
  }
  return value.asLong
}

private fun JsonObject.numericFlag(name: String): Boolean {
  val value = requiredLong(name)
  if (value !in 0L..1L) throw VcsException(LoreBundle.message("error.protocol", name))
  return value == 1L
}

private fun JsonObject.flag(name: String): Boolean {
  val value = get(name)
  if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean) {
    throw VcsException(LoreBundle.message("error.protocol", name))
  }
  return value.asBoolean
}
