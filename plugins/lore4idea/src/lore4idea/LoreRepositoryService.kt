// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package lore4idea

import com.intellij.ide.ActivityTracker
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import lore4idea.commands.EelLoreExecutor
import lore4idea.commands.LoreClient
import lore4idea.commands.LoreProtocol
import lore4idea.commands.LoreSyncCounts
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Service(Service.Level.PROJECT)
internal class LoreRepositoryService(private val project: Project, private val coroutineScope: CoroutineScope) {
  private val rootMutexes = ConcurrentHashMap<String, Mutex>()
  private val branches = ConcurrentHashMap<String, String>()
  private val loadingBranches = ConcurrentHashMap.newKeySet<String>()
  private val branchRetryAfter = ConcurrentHashMap<String, Long>()
  private val counts = ConcurrentHashMap<String, LoreSyncCounts>()
  private val countsErrors = ConcurrentHashMap<String, String>()
  private val loadingCounts = ConcurrentHashMap.newKeySet<String>()
  private val countsRetryAfter = ConcurrentHashMap<String, Long>()
  private val countsGeneration = ConcurrentHashMap<String, AtomicLong>()

  fun syncCountsError(root: VirtualFile): String? = countsErrors[root.url]

  fun cachedSyncCounts(root: VirtualFile): LoreSyncCounts? {
    val key = root.url
    val generation = countsGeneration.computeIfAbsent(key) { AtomicLong() }
    if (System.currentTimeMillis() >= countsRetryAfter.getOrDefault(key, 0) && loadingCounts.add(key)) {
      val expectedGeneration = generation.get()
      coroutineScope.launch(Dispatchers.IO) {
        try {
          val result = withClient(root) { syncCounts() }
          if (!project.isDisposed && generation.get() == expectedGeneration) {
            counts[key] = result
            countsErrors.remove(key)
            rememberBranch(root, result.branch)
          }
        }
        catch (error: VcsException) {
          if (generation.get() == expectedGeneration) {
            counts.remove(key)
            countsErrors[key] = error.message.orEmpty()
          }
        }
        finally {
          if (generation.get() == expectedGeneration) countsRetryAfter[key] = System.currentTimeMillis() + COUNTS_REFRESH_MS
          loadingCounts.remove(key)
          if (!project.isDisposed) ActivityTracker.getInstance().inc()
        }
      }
    }
    return counts[key]?.takeIf { it.branch == branches[key] }
  }

  suspend fun <T> withClient(root: VirtualFile, action: suspend LoreClient.() -> T): T = rootMutexes.computeIfAbsent(root.url) { Mutex() }.withLock {
    val settings = project.service<LoreSettings>().state
    LoreClient(root.toNioPath(), EelLoreExecutor(settings.executable ?: "lore", settings.timeoutSeconds.coerceIn(1, 3600))).action()
  }

  fun root(file: FilePath): VirtualFile {
    val mapping = ProjectLevelVcsManager.getInstance(project).getVcsRootObjectFor(file)
    if (mapping?.vcs?.keyInstanceMethod != LoreVcs.KEY) throw VcsException(LoreBundle.message("error.no.root", file.path))
    return mapping.path
  }

  fun relativePath(root: VirtualFile, file: FilePath): String {
    if (file.isDirectory) throw VcsException(LoreBundle.message("error.directory"))
    val base = root.toNioPath().toAbsolutePath().normalize()
    val path = Path.of(file.path).toAbsolutePath().normalize()
    if (!path.startsWith(base)) throw VcsException(LoreBundle.message("error.path", file.path))
    val relative = base.relativize(path).joinToString("/")
    LoreProtocol.resolvePath(base, relative)
    return relative
  }

  fun cachedBranch(root: VirtualFile): String? {
    requestBranch(root)
    return branches[root.url]
  }

  fun rememberBranch(root: VirtualFile, branch: String) {
    branches[root.url] = branch
    branchRetryAfter.remove(root.url)
    ActivityTracker.getInstance().inc()
  }

  fun requestBranch(root: VirtualFile, force: Boolean = false) {
    val key = root.url
    if (force) {
      branches.remove(key)
      branchRetryAfter.remove(key)
    }
    if (System.currentTimeMillis() < branchRetryAfter.getOrDefault(key, 0)) return
    if (branches.containsKey(key) || !loadingBranches.add(key)) return
    coroutineScope.launch(Dispatchers.IO) {
      try {
        val branch = withClient(root) { status(scan = false).branch }
        if (!project.isDisposed) rememberBranch(root, branch)
      }
      catch (_: VcsException) {
        branchRetryAfter[key] = System.currentTimeMillis() + BRANCH_RETRY_DELAY_MS
      }
      finally {
        loadingBranches.remove(key)
      }
    }
  }

  fun refresh(root: VirtualFile) {
    countsGeneration.computeIfAbsent(root.url) { AtomicLong() }.incrementAndGet()
    counts.remove(root.url)
    countsErrors.remove(root.url)
    countsRetryAfter.remove(root.url)
    requestBranch(root, force = true)
    cachedSyncCounts(root)
    root.refresh(true, true) {
      if (!project.isDisposed) {
        VcsDirtyScopeManager.getInstance(project).dirDirtyRecursively(root)
        project.messageBus.syncPublisher(LoreRepositoryListener.TOPIC).repositoryChanged(root)
      }
    }
  }

  companion object {
    private const val COUNTS_REFRESH_MS = 30_000
    private const val BRANCH_RETRY_DELAY_MS = 10_000
  }
}

internal fun interface LoreRepositoryListener {
  fun repositoryChanged(root: VirtualFile)

  companion object {
    val TOPIC = Topic.create("Lore repository changes", LoreRepositoryListener::class.java)
  }
}
