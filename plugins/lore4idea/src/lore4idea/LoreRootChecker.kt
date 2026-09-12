// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.vcs.VcsRootChecker
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path

internal class LoreRootChecker : VcsRootChecker() {
  override fun getSupportedVcs() = LoreVcs.KEY
  override fun isRoot(path: VirtualFile): Boolean = path.findChild(".lore")?.isDirectory == true
  override fun validateRoot(file: VirtualFile): Boolean = isLoreRoot(file.toNioPath())
  override fun isVcsDir(dirName: String): Boolean = dirName.equals(".lore", ignoreCase = true)

  companion object {
    fun isLoreRoot(path: Path): Boolean = Files.isDirectory(path.resolve(".lore"))
  }
}
