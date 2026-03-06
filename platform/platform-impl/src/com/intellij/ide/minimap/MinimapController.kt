// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap

import com.intellij.ide.minimap.caret.MinimapCaretController
import com.intellij.ide.minimap.geometry.MinimapGeometryCalculator
import com.intellij.ide.minimap.geometry.MinimapScaleUtil
import com.intellij.ide.minimap.layout.MinimapLayoutCalculator
import com.intellij.ide.minimap.listeners.MinimapStateListeners
import com.intellij.ide.minimap.listeners.MinimapUiListeners
import com.intellij.ide.minimap.model.MinimapModel
import com.intellij.ide.minimap.scene.MinimapSceneBuilder
import com.intellij.ide.minimap.settings.MinimapSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.awt.Graphics2D
import javax.swing.JPanel
import kotlin.math.max

@OptIn(FlowPreview::class)
class MinimapController(
  coroutineScope: CoroutineScope,
  private val panel: MinimapPanel,
  private val container: JPanel,
): Disposable {
  private val scope = coroutineScope.childScope("MinimapController")
  private val updates = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private val settings = MinimapSettings.getInstance()
  private val editor: Editor = panel.editor

  private val model = MinimapModel(editor).also {
    Disposer.register(this, it)
  }

  private val layoutCalculator = MinimapLayoutCalculator(editor)
  private val geometryCalculator = MinimapGeometryCalculator(editor)
  private val sceneBuilder = MinimapSceneBuilder(editor, model, layoutCalculator, geometryCalculator)
  private val caretController = MinimapCaretController(editor, panel)

  private val stateListeners = MinimapStateListeners(
    parentDisposable = this,
    editor = editor,
    caretController = caretController,
    scheduleStructureMarkersUpdate = ::scheduleStructureMarkersUpdate,
    updateParameters = ::refreshSnapshot,
    repaint = panel::repaint,
  )

  private val uiListeners = MinimapUiListeners(
    parentDisposable = this,
    container = container,
    contentComponent = editor.contentComponent,
    updateParameters = ::refreshSnapshot,
    revalidate = panel::revalidate,
    repaint = panel::repaint
  )

  fun install() {
    stateListeners.install()
    uiListeners.install()
    refreshSnapshot()
    initStructureMarkersFlow()
  }

  override fun dispose() {
    sceneBuilder.clear()
    scope.cancel()
  }

  fun isDocumentCommitted(): Boolean = model.isDocumentCommitted()

  fun paintCaret(graphics: Graphics2D): Unit = caretController.paint(graphics)

  fun scheduleStructureMarkersUpdate(): Boolean = updates.tryEmit(Unit)

  fun refreshSnapshot() {
    val state = settings.state
    val panelHeight = max(panel.height, 0)
    val scaleData = MinimapScaleUtil.computeScale(editor, panelHeight, state.width, state.scaleMode)
    if (panel.updatePreferredWidth(scaleData.width)) {
      panel.revalidate()
    }

    val panelWidth = max(panel.width, scaleData.width)
    val snapshot = sceneBuilder.buildSnapshot(panelWidth, panelHeight, scaleData, state.scaleMode, MinimapRegistry.isLegacy())
    panel.updateSnapshot(snapshot)
  }

  fun updateStructureMarkersNow() {
    ReadAction.nonBlocking<Unit> { model.updateStructureMarkers() }
      .coalesceBy(this)
      .expireWith(this)
      .finishOnUiThread(ModalityState.any()) {
        refreshSnapshot()
        panel.repaint()
      }.submit(AppExecutorUtil.getAppExecutorService())
  }

  private fun initStructureMarkersFlow() = scope.launch {
    updates.debounce(STRUCTURE_MARKERS_DEBOUNCE_MS).collect {
      updateStructureMarkersNow()
    }
  }

  companion object {
    private const val STRUCTURE_MARKERS_DEBOUNCE_MS: Long = 125
  }
}
