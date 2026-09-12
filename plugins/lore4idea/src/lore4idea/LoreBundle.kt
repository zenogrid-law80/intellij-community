// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

internal object LoreBundle : DynamicBundle(LoreBundle::class.java, "messages.LoreBundle") {
  @Nls
  fun message(@PropertyKey(resourceBundle = "messages.LoreBundle") key: String, vararg params: Any): String = getMessage(key, *params)
}
