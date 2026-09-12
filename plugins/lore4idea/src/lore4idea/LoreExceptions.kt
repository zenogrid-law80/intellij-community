// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import java.util.concurrent.CancellationException

internal fun rethrowCancellation(error: Throwable) {
  if (error is CancellationException) throw error
}
