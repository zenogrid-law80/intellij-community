// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea.log

import com.intellij.vcs.log.RefGroup
import com.intellij.vcs.log.VcsLogRefManager
import com.intellij.vcs.log.VcsLogStandardColors
import com.intellij.vcs.log.VcsRef
import com.intellij.vcs.log.VcsRefType
import com.intellij.vcs.log.impl.SimpleRefGroup
import com.intellij.vcs.log.impl.SimpleRefType
import lore4idea.LoreBundle
import java.io.DataInput
import java.io.DataOutput
import java.util.Comparator

internal class LoreRefManager : VcsLogRefManager {
  private val comparator = compareBy<VcsRef>({ it.name }, { it.root.path })

  override fun getBranchLayoutComparator(): Comparator<VcsRef> = comparator
  override fun getLabelsOrderComparator(): Comparator<VcsRef> = comparator

  override fun groupForBranchFilter(refs: Collection<VcsRef>): List<RefGroup> =
    if (refs.isEmpty()) emptyList()
    else listOf(SimpleRefGroup(LoreBundle.message("log.ref.group.local"), refs.sortedWith(comparator).toMutableList()))

  override fun groupForTable(refs: Collection<VcsRef>, compact: Boolean, showTagNames: Boolean): List<RefGroup> =
    refs.sortedWith(comparator).map { SimpleRefGroup(it.name, mutableListOf(it)) }

  override fun serialize(out: DataOutput, type: VcsRefType) = out.writeByte(0)
  override fun deserialize(input: DataInput): VcsRefType = input.readByte().let { BRANCH }
  override fun isFavorite(reference: VcsRef): Boolean = false
  override fun setFavorite(reference: VcsRef, favorite: Boolean) = Unit

  companion object {
    val BRANCH: VcsRefType = SimpleRefType("BRANCH", true, VcsLogStandardColors.Refs.BRANCH)
  }
}
