// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet", "ReplacePutWithAssignment")

package com.intellij.agent.workbench.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileOpenedSyncListener
import com.intellij.openapi.fileEditor.ex.FileEditorWithProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeps the live terminal session bound to the logical chat tab identified by [AgentChatVirtualFile.tabKey].
 *
 * Editor widgets may be disposed and recreated by the IDE during drag-and-drop tab moves, split changes, or
 * detach/reattach flows. Those UI transitions must not restart or interrupt the underlying agent session.
 */
internal interface AgentChatLiveTerminalRegistry {
  /**
   * Returns the existing live terminal for [file], or creates it on first attachment.
   */
  fun acquireOrCreate(file: AgentChatVirtualFile, terminalTabs: AgentChatTerminalTabs): AgentChatTerminalTab
}

/**
 * Project-scoped implementation that owns cleanup for retained chat terminals.
 *
 * Terminals are released only when the last open copy of the chat file closes, or when the project itself is disposed.
 */
@Service(Service.Level.PROJECT)
internal class AgentChatLiveTerminalRegistryService(
  private val project: Project,
  private val serviceScope: CoroutineScope,
) : AgentChatLiveTerminalRegistry, Disposable {
  private val store = AgentChatLiveTerminalStore()
  private val pendingCloseJobs = ConcurrentHashMap<String, Job>()

  init {
    project.messageBus.connect(this).apply {
      subscribe(
        FileEditorManagerListener.FILE_EDITOR_MANAGER,
        object : FileEditorManagerListener {
          override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
            val chatFile = file as? AgentChatVirtualFile ?: return
            when (store.handleFileClosed(project = project, source = source, file = chatFile)) {
              AgentChatLiveTerminalCloseResult.DEFERRED -> schedulePendingCloseConfirmation(source = source, file = chatFile)
              AgentChatLiveTerminalCloseResult.KEPT_OPEN, AgentChatLiveTerminalCloseResult.CLOSED ->
                cancelPendingCloseJob(chatFile.tabKey)
            }
          }
        }
      )
      subscribe(
        FileOpenedSyncListener.TOPIC,
        object : FileOpenedSyncListener {
          override fun fileOpenedSync(
            source: FileEditorManager,
            file: VirtualFile,
            editorsWithProviders: List<FileEditorWithProvider>,
          ) {
            val chatFile = file as? AgentChatVirtualFile ?: return
            cancelPendingCloseJob(chatFile.tabKey)
            store.handleFileOpened(chatFile)
          }
        }
      )
    }
  }

  override fun acquireOrCreate(file: AgentChatVirtualFile, terminalTabs: AgentChatTerminalTabs): AgentChatTerminalTab {
    cancelPendingCloseJob(file.tabKey)
    return store.acquireOrCreate(project = project, file = file, terminalTabs = terminalTabs)
  }

  override fun dispose() {
    pendingCloseJobs.values.forEach(Job::cancel)
    pendingCloseJobs.clear()
    store.dispose(project)
  }

  private fun schedulePendingCloseConfirmation(source: FileEditorManager, file: AgentChatVirtualFile) {
    val tabKey = file.tabKey
    val job = serviceScope.launch(start = CoroutineStart.LAZY) {
      repeat(PENDING_CLOSE_CONFIRMATION_RECHECK_COUNT) {
        delay(PENDING_CLOSE_CONFIRMATION_RECHECK_INTERVAL_MS.milliseconds)
        val reopened = withContext(Dispatchers.EDT) {
          if (project.isDisposed) {
            false
          }
          else {
            source.isFileOpen(file)
          }
        }
        if (reopened) {
          withContext(Dispatchers.EDT) {
            store.handleFileOpened(file)
          }
          return@launch
        }
      }

      withContext(Dispatchers.EDT) {
        if (!project.isDisposed) {
          store.confirmPendingClose(project = project, source = source, file = file)
        }
      }
    }
    registerPendingCloseJob(tabKey = tabKey, job = job)
    job.start()
  }

  private fun registerPendingCloseJob(tabKey: String, job: Job) {
    pendingCloseJobs.put(tabKey, job)?.cancel()
    job.invokeOnCompletion {
      pendingCloseJobs.remove(tabKey, job)
    }
  }

  private fun cancelPendingCloseJob(tabKey: String) {
    pendingCloseJobs.remove(tabKey)?.cancel()
  }
}

internal enum class AgentChatLiveTerminalCloseResult {
  KEPT_OPEN,
  DEFERRED,
  CLOSED,
}

/**
 * Synchronized in-memory store used by the project service and lightweight lifecycle tests.
 */
internal class AgentChatLiveTerminalStore {
  private data class LiveTerminalEntry(
    val tab: AgentChatTerminalTab,
    val terminalTabs: AgentChatTerminalTabs,
  )

  private val entries = LinkedHashMap<String, LiveTerminalEntry>()
  private val pendingCloseTabKeys = LinkedHashSet<String>()

  /**
   * Reuses the retained terminal for the same logical tab, preserving the running session across editor recreation.
   */
  @Synchronized
  fun acquireOrCreate(project: Project, file: AgentChatVirtualFile, terminalTabs: AgentChatTerminalTabs): AgentChatTerminalTab {
    pendingCloseTabKeys.remove(file.tabKey)
    val existing = entries.get(file.tabKey)
    if (existing != null) {
      return existing.tab
    }

    val createdTab = terminalTabs.createTab(project, file)
    entries.put(file.tabKey, LiveTerminalEntry(tab = createdTab, terminalTabs = terminalTabs))
    return createdTab
  }

  /**
   * Closes the retained terminal only after the IDE reports that no copy of [file] remains open.
   */
  @Synchronized
  fun handleFileClosed(
    project: Project,
    source: FileEditorManager,
    file: AgentChatVirtualFile,
  ): AgentChatLiveTerminalCloseResult {
    if (source.isFileOpen(file)) {
      pendingCloseTabKeys.remove(file.tabKey)
      return AgentChatLiveTerminalCloseResult.KEPT_OPEN
    }

    if (file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true) {
      pendingCloseTabKeys.add(file.tabKey)
      return AgentChatLiveTerminalCloseResult.DEFERRED
    }

    pendingCloseTabKeys.remove(file.tabKey)
    return closeAndRemove(project = project, tabKey = file.tabKey)
  }

  @Synchronized
  fun handleFileOpened(file: AgentChatVirtualFile) {
    pendingCloseTabKeys.remove(file.tabKey)
  }

  @Synchronized
  fun confirmPendingClose(
    project: Project,
    source: FileEditorManager,
    file: AgentChatVirtualFile,
  ): AgentChatLiveTerminalCloseResult {
    if (!pendingCloseTabKeys.remove(file.tabKey)) {
      return AgentChatLiveTerminalCloseResult.KEPT_OPEN
    }
    if (source.isFileOpen(file)) {
      return AgentChatLiveTerminalCloseResult.KEPT_OPEN
    }
    return closeAndRemove(project = project, tabKey = file.tabKey)
  }

  /**
   * Releases every retained terminal during project shutdown.
   */
  @Synchronized
  fun dispose(project: Project) {
    val entriesToClose = entries.values.toList()
    entries.clear()
    pendingCloseTabKeys.clear()
    for (entry in entriesToClose) {
      entry.terminalTabs.closeTab(project, entry.tab)
    }
  }

  @TestOnly
  @Synchronized
  fun isTracked(tabKey: String): Boolean {
    return entries.containsKey(tabKey)
  }

  @TestOnly
  @Synchronized
  fun isPendingClose(tabKey: String): Boolean {
    return pendingCloseTabKeys.contains(tabKey)
  }

  private fun closeAndRemove(project: Project, tabKey: String): AgentChatLiveTerminalCloseResult {
    val entry = entries.remove(tabKey) ?: return AgentChatLiveTerminalCloseResult.KEPT_OPEN
    entry.terminalTabs.closeTab(project, entry.tab)
    return AgentChatLiveTerminalCloseResult.CLOSED
  }
}

private const val PENDING_CLOSE_CONFIRMATION_RECHECK_INTERVAL_MS = 50L
private const val PENDING_CLOSE_CONFIRMATION_RECHECK_COUNT = 10
