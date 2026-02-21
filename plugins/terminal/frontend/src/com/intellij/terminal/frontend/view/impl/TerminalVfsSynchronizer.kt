package com.intellij.terminal.frontend.view.impl

import com.intellij.ide.GeneralSettings
import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.observable.util.addFocusListener
import com.intellij.platform.util.coroutines.childScope
import com.intellij.terminal.frontend.view.TerminalView
import com.intellij.util.asDisposable
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandExecutionListener
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandFinishedEvent
import java.awt.event.FocusEvent
import java.awt.event.FocusListener

internal class TerminalVfsSynchronizer private constructor(
  terminalView: TerminalView,
  coroutineScope: CoroutineScope,
) {
  init {
    val disposable = coroutineScope.asDisposable()

    // Use a heuristic-based command finish tracker for refreshing VFS by default.
    // But if we receive the event about available shell integration, it will be canceled.
    val heuristicBasedRefresherScope = coroutineScope.childScope("Heuristic based VFS refresher")
    TerminalHeuristicsBasedCommandFinishTracker.install(
      terminalView,
      heuristicBasedRefresherScope,
      onCommandFinish = {
        SaveAndSyncHandler.getInstance().scheduleRefresh()
        LOG.debug { "Command finished, schedule VFS refresh." }
      }
    )

    coroutineScope.launch(CoroutineName("Shell integration awaiting")) {
      val shellIntegration = terminalView.shellIntegrationDeferred.await()

      // If we have events from the shell integration, we no more need heuristic-based refresher.
      heuristicBasedRefresherScope.cancel()
      LOG.debug { "Shell integration initialized, cancel heuristic-based VFS refresher." }

      shellIntegration.addCommandExecutionListener(disposable, object : TerminalCommandExecutionListener {
        override fun commandFinished(event: TerminalCommandFinishedEvent) {
          SaveAndSyncHandler.getInstance().scheduleRefresh()
          LOG.debug { "Command finished, schedule VFS refresh." }
        }
      })
    }

    terminalView.component.addFocusListener(disposable, object : FocusListener {
      override fun focusGained(e: FocusEvent) {
        if (GeneralSettings.getInstance().isSaveOnFrameDeactivation) {
          WriteIntentReadAction.run {
            FileDocumentManager.getInstance().saveAllDocuments()
            LOG.debug { "Focus gained, save all documents to VFS." }
          }
        }
      }

      override fun focusLost(e: FocusEvent) {
        if (GeneralSettings.getInstance().isSyncOnFrameActivation) {
          // Like we sync the external changes when switching to the IDE window, let's do the same
          // when the focus is transferred from the built-in terminal to some other IDE place.
          // To get the updates from a long-running command in the built-in terminal.
          SaveAndSyncHandler.getInstance().scheduleRefresh()
          LOG.debug { "Focus lost, schedule VFS refresh." }
        }
      }
    })
  }

  companion object {
    private val LOG = logger<TerminalVfsSynchronizer>()

    fun install(terminalView: TerminalView, coroutineScope: CoroutineScope) {
      TerminalVfsSynchronizer(terminalView, coroutineScope)
    }
  }
}