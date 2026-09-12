// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.commands

import com.intellij.openapi.vcs.VcsException
import lore4idea.LoreBundle
import java.nio.file.Path

internal data class LoreFolderListing(val revision: String, val folders: List<String>)

internal fun parseLoreFolders(root: Path, parent: String, revision: String, events: List<LoreEvent>): LoreFolderListing {
  if (events.singleOrNull { it.tag == "repositoryDumpBegin" }?.data?.requiredString("revision") != revision ||
      events.none { it.tag == "repositoryDumpEnd" }) {
    throw VcsException(LoreBundle.message("error.protocol", "repositoryDump"))
  }
  val folders = sortedSetOf<String>()
  val basename = parent.substringAfterLast('/')
  for (event in events.filter { it.tag == "repositoryStateDumpNode" }) {
    val name = event.data.requiredString("name")
    if (!name.endsWith('/')) continue
    val relative = name.dropLast(1)
    if (parent.isNotEmpty() && relative == basename) continue
    val child = if (parent.isEmpty()) relative else {
      if (!relative.startsWith("$basename/")) throw VcsException(LoreBundle.message("error.protocol", name))
      relative.removePrefix("$basename/")
    }
    if (child.contains('/')) throw VcsException(LoreBundle.message("error.protocol", name))
    LoreProtocol.resolvePath(root, child)
    folders.add(if (parent.isEmpty()) child else "$parent/$child")
  }
  return LoreFolderListing(revision, folders.toList())
}
