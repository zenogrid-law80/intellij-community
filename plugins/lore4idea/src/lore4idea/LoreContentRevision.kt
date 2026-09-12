// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.components.service
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.ByteBackedContentRevision
import com.intellij.openapi.vcs.history.VcsRevisionNumber
import com.intellij.openapi.vfs.VirtualFile

internal data class LoreRevisionNumber(val hash: String) : VcsRevisionNumber {
  override fun asString(): String = hash
  override fun compareTo(other: VcsRevisionNumber): Int = hash.compareTo(other.asString())
}

internal class LoreContentRevision(
  private val project: Project,
  private val root: VirtualFile,
  private val file: FilePath,
  private val number: LoreRevisionNumber,
) : ByteBackedContentRevision {
  override fun getFile() = file
  override fun getRevisionNumber() = number
  override fun getContent(): String = getContentAsBytes().toString(file.charset)
  override fun getContentAsBytes(): ByteArray = runBlockingCancellable {
    val repositories = project.service<LoreRepositoryService>()
    val path = repositories.relativePath(root, file)
    repositories.withClient(root) { content(path, number.hash) }
  }
}
