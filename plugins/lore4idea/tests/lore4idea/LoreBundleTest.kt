// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Properties

internal class LoreBundleTest {
  @Test
  fun `localized bundles keep the default keys and placeholders`() {
    val defaults = load("LoreBundle.properties")
    for (localized in listOf("LoreBundle_ko.properties", "LoreBundle_zh_CN.properties")) {
      val values = load(localized)
      assertEquals(defaults.stringPropertyNames(), values.stringPropertyNames(), localized)
      for (key in defaults.stringPropertyNames()) {
        assertEquals(placeholders(defaults.getProperty(key)), placeholders(values.getProperty(key)), "$localized: $key")
      }
    }
  }

  private fun load(name: String): Properties = Properties().apply {
    val input = checkNotNull(LoreBundle::class.java.getResourceAsStream("/messages/$name"))
    input.use { load(InputStreamReader(it, UTF_8)) }
  }

  private fun placeholders(value: String): List<String> = PLACEHOLDER.findAll(value).map { it.value }.toList()

  companion object {
    private val PLACEHOLDER = Regex("\\{\\d+}")
  }
}
