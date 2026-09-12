package lore4idea

import com.intellij.openapi.util.SystemInfoRt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class LoreSettingsTest {
  @TempDir
  lateinit var tempDirectory: Path

  @Test
  fun `detects the Lore executable on PATH`() {
    val executableName = if (SystemInfoRt.isWindows) "lore.exe" else "lore"
    val executable = Files.createFile(tempDirectory.resolve(executableName))
    if (!SystemInfoRt.isWindows) {
      Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
    }

    assertEquals(executable.toString(), detectLoreExecutable(tempDirectory.toString()))
  }

  @Test
  fun `uses the command name when Lore is absent from PATH`() {
    assertEquals("lore", detectLoreExecutable(tempDirectory.toString()))
  }
}
