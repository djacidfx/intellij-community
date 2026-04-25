// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

@file:Suppress("ReplaceGetOrSet")

package com.intellij.agent.workbench.codex.sessions.backend.rollout

// @spec community/plugins/agent-workbench/spec/agent-sessions-codex-rollout-source.spec.md

import com.intellij.agent.workbench.codex.common.normalizeRootPath
import com.intellij.agent.workbench.codex.sessions.backend.CodexBackendThread
import com.intellij.agent.workbench.codex.sessions.backend.CodexSessionBackend
import com.intellij.agent.workbench.codex.sessions.resolveProjectDirectoryFromPath
import com.intellij.agent.workbench.json.filebacked.FileBackedSessionChangeSet
import com.intellij.agent.workbench.json.filebacked.createFileBackedSessionChangeFlow
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

private val LOG = logger<CodexRolloutSessionBackend>()
private const val CODEX_ROLLOUT_TRAILING_REFRESH_DELAY_MS = 250L

internal class CodexRolloutSessionBackend(
  private val codexHomeProvider: () -> Path = { Path.of(System.getProperty("user.home"), ".codex") },
  rolloutChangeSource: (() -> Flow<FileBackedSessionChangeSet>)? = null,
  private val trailingRefreshDelayMs: Long = CODEX_ROLLOUT_TRAILING_REFRESH_DELAY_MS,
) : CodexSessionBackend {
  private val parser = CodexRolloutParser()
  private val threadIndex = CodexRolloutThreadIndex(codexHomeProvider = codexHomeProvider, parser = parser)

  override val updates: Flow<Unit> = createUpdatesFlow(rolloutChangeSource?.invoke() ?: createWatcherUpdates())
    .conflate()

  private fun createUpdatesFlow(sourceUpdates: Flow<FileBackedSessionChangeSet>): Flow<Unit> {
    return channelFlow {
      var trailingRefreshJob: Job? = null
      sourceUpdates.collect { changeSet ->
        threadIndex.markDirty(changeSet)
        send(Unit)

        trailingRefreshJob?.cancel()
        trailingRefreshJob = launch {
          delay(trailingRefreshDelayMs)
          send(Unit)
        }
      }
    }
  }

  private fun createWatcherUpdates(): Flow<FileBackedSessionChangeSet> {
    return createFileBackedSessionChangeFlow(
      logger = LOG,
      watcherName = "Codex rollout",
      initContext = { "codexHome=${codexHomeProvider()}" },
      emitInitialRefreshPing = true,
    ) { scope, onChange ->
      CodexRolloutSessionsWatcher(
        codexHomeProvider = codexHomeProvider,
        scope = scope,
        onRolloutChange = onChange,
      )
    }
  }

  override suspend fun listThreads(path: String, @Suppress("UNUSED_PARAMETER") openProject: Project?): List<CodexBackendThread> {
    return withContext(Dispatchers.IO) {
      val workingDirectory = resolveProjectDirectoryFromPath(path)
        ?: return@withContext emptyList()
      val cwdFilter = normalizeRootPath(workingDirectory.invariantSeparatorsPathString)
      threadIndex.collectByCwd(setOf(cwdFilter))[cwdFilter].orEmpty()
    }
  }

  override suspend fun prefetchThreads(paths: List<String>): Map<String, List<CodexBackendThread>> {
    return withContext(Dispatchers.IO) {
      val pathFilters = resolvePathFilters(paths)
      if (pathFilters.isEmpty()) return@withContext emptyMap()

      val threadsByCwd = threadIndex.collectByCwd(pathFilters.mapTo(HashSet(pathFilters.size)) { (_, cwdFilter) -> cwdFilter })
      pathFilters.associate { (path, cwdFilter) ->
        path to threadsByCwd.get(cwdFilter).orEmpty()
      }
    }
  }
}

private fun resolvePathFilters(paths: List<String>): List<Pair<String, String>> {
  return paths.mapNotNull { path ->
    resolveProjectDirectoryFromPath(path)?.let { directory ->
      path to normalizeRootPath(directory.invariantSeparatorsPathString)
    }
  }
}
