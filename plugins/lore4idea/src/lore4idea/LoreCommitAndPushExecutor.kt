// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.util.Key
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.CommitExecutor
import com.intellij.openapi.vcs.changes.CommitSession

private val PUSH_AFTER_COMMIT = Key.create<Boolean>("Lore.Commit.PushAfterCommit")

internal val CommitContext.isLorePushAfterCommit: Boolean
  get() = getUserData(PUSH_AFTER_COMMIT) == true

internal class LoreCommitAndPushExecutor : CommitExecutor {
  override fun getActionText(): String = LoreBundle.message("operation.commit.and.push")
  override fun getId(): String = ID
  override fun requiresSyncCommitChecks(): Boolean = true

  override fun createCommitSession(commitContext: CommitContext): CommitSession {
    commitContext.putUserData(PUSH_AFTER_COMMIT, true)
    return CommitSession.VCS_COMMIT
  }

  companion object {
    private const val ID = "Lore.Commit.And.Push.Executor"
  }
}
