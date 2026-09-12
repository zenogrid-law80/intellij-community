// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.commands

import com.intellij.openapi.vcs.VcsException
import lore4idea.LoreBundle

internal enum class LoreFolderState { INCLUDED, EXCLUDED, MIXED }
internal data class LoreFolderSelection(val path: String, val included: Boolean)

internal object LoreFolderRules {
  private const val MARKER = "# Lore: folder selections"
  private const val LORELENS_MARKER = "# LoreLens: folder selections"

  fun select(changes: MutableList<LoreFolderSelection>, selection: LoreFolderSelection) {
    validate(selection.path)
    changes.removeAll { selection.path.isEmpty() || it.path.equals(selection.path, true) ||
                        it.path.startsWith(selection.path + "/", true) }
    changes.add(selection)
  }

  fun state(text: String, path: String): LoreFolderState {
    var state = LoreFolderState.INCLUDED
    for (line in text.lineSequence()) {
      var rule = line.trim()
      if (rule.isEmpty() || rule.startsWith('#')) continue
      var included = false
      while (rule.startsWith('!')) {
        included = !included
        rule = rule.drop(1)
      }
      val next = if (included) LoreFolderState.INCLUDED else LoreFolderState.EXCLUDED
      if (rule == "**" || rule == "/**") {
        state = next
        continue
      }
      val literal = if (rule.startsWith('/')) decode(rule.drop(1).removeSuffix("/**").trimEnd('/')) else null
      if (literal == null) {
        state = LoreFolderState.MIXED
      }
      else if (path.equals(literal, true) || path.startsWith("$literal/", true)) {
        state = next
      }
      else if ((path.isEmpty() || literal.startsWith("$path/", true)) && state != next) {
        state = LoreFolderState.MIXED
      }
    }
    return state
  }

  fun merge(original: String, changes: List<LoreFolderSelection>): String {
    if (changes.isEmpty()) return original
    val output = StringBuilder()
    val pending = mutableListOf<LoreFolderSelection>()
    val lines = Regex("[^\\n]*\\n|[^\\n]+$").findAll(original).map { it.value }.toList()
    var managed = false
    var marker = MARKER
    var index = 0
    fun flush() {
      if (pending.isNotEmpty()) {
        appendBlock(output, pending)
        pending.clear()
      }
    }
    while (index < lines.size) {
      val line = lines[index].trim()
      if (line == MARKER || line == LORELENS_MARKER) {
        managed = true
        marker = line
        index++
        continue
      }
      if (managed) {
        if (line.isEmpty()) {
          index++
          continue
        }
        if (line == "**" || line == "!/**") {
          select(pending, LoreFolderSelection("", line == "!/**"))
          index++
          continue
        }
        var second = index + 1
        while (second < lines.size && lines[second].isBlank()) second++
        val pair = if (second < lines.size) parsePair(line, lines[second].trim()) else null
        if (pair != null) {
          select(pending, pair)
          index = second + 1
          continue
        }
        if (pending.isEmpty()) output.append(marker).append('\n') else flush()
        managed = false
      }
      output.append(lines[index++])
    }
    changes.forEach { select(pending, it) }
    flush()
    return output.toString()
  }

  private fun appendBlock(output: StringBuilder, changes: List<LoreFolderSelection>) {
    if (output.isNotEmpty() && output.last() != '\n') output.append('\n')
    output.append(MARKER).append('\n')
    for ((index, selection) in changes.withIndex()) {
      val ancestor = changes.take(index).lastOrNull { it.path.isEmpty() || selection.path.startsWith(it.path + "/", true) }
      if (ancestor?.included == selection.included) continue
      if (selection.path.isEmpty()) {
        output.append(if (selection.included) "!/**\n" else "**\n")
      }
      else {
        val prefix = (if (selection.included) "!/" else "/") + encode(selection.path) + "/"
        output.append(prefix).append('\n').append(prefix).append("**\n")
      }
    }
  }

  private fun parsePair(first: String, second: String): LoreFolderSelection? {
    val pattern = first.removePrefix("!")
    if (!pattern.startsWith('/') || !pattern.endsWith('/') || second != first + "**") return null
    val path = decode(pattern.drop(1).dropLast(1)) ?: return null
    if (!valid(path) || path.isEmpty()) return null
    return LoreFolderSelection(path, first.startsWith('!'))
  }

  private fun encode(path: String): String = buildString {
    for (character in path) {
      if (character in "*?[]{}\\") append('\\')
      append(character)
    }
  }

  private fun decode(pattern: String): String? {
    val result = StringBuilder()
    var escaped = false
    for (character in pattern) {
      if (escaped) {
        result.append(character)
        escaped = false
      }
      else if (character == '\\') escaped = true
      else if (character in "*?[]{}") return null
      else result.append(character)
    }
    return if (escaped) null else result.toString()
  }

  private fun valid(path: String): Boolean = path.isEmpty() ||
    path.none { it in "\\\n\r\u0000:" } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

  private fun validate(path: String) {
    if (!valid(path)) throw VcsException(LoreBundle.message("error.path", path))
  }
}
