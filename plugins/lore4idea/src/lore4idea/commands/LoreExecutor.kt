// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.commands

import com.intellij.openapi.vcs.VcsException
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.platform.eel.provider.utils.lines
import com.intellij.platform.eel.spawnProcess
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withTimeoutOrNull
import lore4idea.LoreBundle
import lore4idea.rethrowCancellation
import java.nio.file.Path

internal fun interface LoreExecutor {
  suspend fun execute(root: Path, arguments: List<String>): String
}

internal class EelLoreExecutor(private val executable: String, private val timeoutSeconds: Int) : LoreExecutor {
  override suspend fun execute(root: Path, arguments: List<String>): String {
    try {
      val result = withTimeoutOrNull(timeoutSeconds.toLong() * 1000) {
        coroutineScope {
          val realRoot = root.toRealPath()
          val eelRoot = realRoot.asEelPath()
          val api = realRoot.getEelDescriptor().toEelApi()
          val builder = api.exec.spawnProcess(executable)
            .args(listOf("--json", "--no-pager", "--non-interactive", "--repository", eelRoot.toString()) + arguments)
            .workingDirectory(eelRoot)
          val process = builder.scope(this).eelIt()
          process.stdin.close(null)
          val stderr = async {
            val output = StringBuilder()
            process.stderr.lines(Charsets.UTF_8).collect { line -> appendOutput(output, line) }
            output.toString()
          }
          val stdout = StringBuilder()
          process.stdout.lines(Charsets.UTF_8).collect { line ->
            appendOutput(stdout, line)
          }
          LoreProcessResult(process.exitCode.await(), stdout.toString(), stderr.await())
        }
      } ?: throw VcsException(LoreBundle.message("error.timeout"))
      val output = result.stdout
      if (result.exitCode != 0 && output.isBlank()) {
        throw VcsException(LoreBundle.message("error.command", result.stderr.ifBlank { "${result.exitCode}" }))
      }
      return output
    }
    catch (e: Exception) {
      rethrowCancellation(e)
      if (e is VcsException) throw e
      throw VcsException(LoreBundle.message("error.command", e.message.orEmpty()), e)
    }
  }

  private fun appendOutput(output: StringBuilder, line: String) {
    if (line.length > MAX_OUTPUT_CHARS - output.length - 1) {
      throw VcsException(LoreBundle.message("error.output.too.large", MAX_OUTPUT_CHARS / (1024 * 1024)))
    }
    output.append(line).append('\n')
  }

  private data class LoreProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

  companion object {
    private const val MAX_OUTPUT_CHARS = 16 * 1024 * 1024
  }
}
