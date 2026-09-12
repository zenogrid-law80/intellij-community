// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.commands

import com.intellij.openapi.vcs.VcsException
import lore4idea.LoreBundle
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes

internal class LoreViewFile(root: Path) {
  private val path = root.resolve(".lore/view")

  fun read(): String? = try {
    if (!Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isRegularFile) {
      throw VcsException(LoreBundle.message("error.view.not.file"))
    }
    Files.readString(path)
  }
  catch (_: NoSuchFileException) {
    null
  }

  fun write(expected: String?, content: String?) {
    if (read() != expected) throw VcsException(LoreBundle.message("error.view.changed"))
    if (content == expected) return
    if (content == null) {
      Files.delete(path)
      return
    }
    val temporary = Files.createTempFile(path.parent, "view-", ".tmp")
    try {
      Files.writeString(temporary, content)
      if (read() != expected) throw VcsException(LoreBundle.message("error.view.changed"))
      try {
        Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING)
      }
      catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, path, REPLACE_EXISTING)
      }
    }
    finally {
      Files.deleteIfExists(temporary)
    }
  }
}
