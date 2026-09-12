// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import lore4idea.log.loreUserIdentity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class LoreLogUserTest {
  @Test
  fun `keeps the author and committer distinct`() {
    val metadata = mapOf("created-by" to "author", "committed-by" to "committer")
    val names = mapOf("author" to "First User", "committer" to "Second User")
    assertEquals("First User", loreUserIdentity(metadata, names))
    assertEquals("Second User", loreUserIdentity(metadata, names, committer = true))
  }

  @Test
  fun `preserves an unresolved identity`() {
    assertEquals("original-id", loreUserIdentity(mapOf("created-by" to "original-id"), emptyMap()))
    assertEquals("original-id", loreUserIdentity(mapOf("created-by" to "original-id"), mapOf("original-id" to "")))
  }

  @Test
  fun `does not attribute missing authorship to the current user`() {
    assertEquals(LoreBundle.message("log.unknown.user"), loreUserIdentity(emptyMap(), emptyMap()))
    assertEquals("committer", loreUserIdentity(mapOf("created-by" to " ", "committed-by" to "committer"), emptyMap()))
  }
}
