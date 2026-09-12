// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.VcsKey
import com.intellij.openapi.vcs.VcsType
import lore4idea.history.LoreHistoryProvider

internal class LoreVcs(project: Project) : AbstractVcs(project, NAME) {
  private val commitAndPushExecutor = LoreCommitAndPushExecutor()

  override fun getDisplayName() = LoreBundle.message("lore.name")
  override fun getType() = VcsType.distributed
  override fun getChangeProvider() = LoreChangeProvider(project)
  override fun getDiffProvider() = LoreDiffProvider(project)
  override fun getVcsHistoryProvider() = LoreHistoryProvider(project)
  override fun getConfigurable() = LoreConfigurable(project)
  override fun createCheckinEnvironment() = LoreCheckinEnvironment(project)
  override fun createRollbackEnvironment() = LoreRollbackEnvironment(project)
  override fun getCommitExecutors() = listOf(commitAndPushExecutor)

  companion object {
    const val NAME = "Lore"
    val KEY: VcsKey = createKey(NAME)
  }
}
