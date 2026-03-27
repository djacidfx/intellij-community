// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap

import com.intellij.ide.minimap.caret.MinimapCaretController
import com.intellij.ide.minimap.geometry.MinimapGeometryCalculator
import com.intellij.ide.minimap.geometry.MinimapScaleUtil
import com.intellij.ide.minimap.layout.MinimapLayoutCalculator
import com.intellij.ide.minimap.listeners.MinimapStateListeners
import com.intellij.ide.minimap.listeners.MinimapUiListeners
import com.intellij.ide.minimap.model.MinimapModel
import com.intellij.ide.minimap.scene.MinimapSnapshot
import com.intellij.ide.minimap.scene.MinimapSceneBuilder
import com.intellij.ide.minimap.settings.MinimapSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.platform.util.coroutines.childScope
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Graphics2D
import javax.swing.JPanel
import kotlin.math.max

@OptIn(FlowPreview::class)
class MinimapController(
  coroutineScope: CoroutineScope,
  private val panel: MinimapPanel,
  private val container: JPanel,
): Disposable {
  @Volatile
  private var disposed = false

  private val scope = coroutineScope.childScope("MinimapController")
  private val structureUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private val diagnosticsUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  private val settings = MinimapSettings.getInstance()
  private val editor: Editor = panel.editor
  private var snapshotSequence: Long = 0
  private var maxTokenEntries: Int = 0
  private var maxStructureEntries: Int = 0
  private var maxDiagnosticEntries: Int = 0
  private var maxBreakpointEntries: Int = 0
  private var maxFoldEntries: Int = 0
  private var maxEstimatedSnapshotBytes: Long = 0

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
    scheduleDiagnosticsUpdate = { scheduleDiagnosticsUpdate() },
    scheduleBreakpointsUpdate = { scheduleDiagnosticsUpdate() },
    scheduleFoldingUpdate = { scheduleDiagnosticsUpdate() },
    invalidateLineProjection = model::invalidateLineProjection,
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
    initDiagnosticsFlow()
  }

  override fun dispose() {
    disposed = true
    sceneBuilder.clear()
    scope.cancel()
  }

  fun isDocumentCommitted(): Boolean = model.isDocumentCommitted()

  fun paintCaret(graphics: Graphics2D): Unit = caretController.paint(graphics)

  fun scheduleStructureMarkersUpdate(): Boolean = structureUpdates.tryEmit(Unit)

  fun scheduleDiagnosticsUpdate(): Boolean = diagnosticsUpdates.tryEmit(Unit)

  fun refreshSnapshot() {
    val state = settings.state
    val panelHeight = max(if (panel.height > 0) panel.height else container.height, 0)
    val scaleData = MinimapScaleUtil.computeScale(editor, panelHeight, state.width, state.scaleMode)
    if (!updatePanelVisibility(scaleData.width)) {
      return
    }
    if (panel.updatePreferredWidth(scaleData.width)) {
      panel.revalidate()
    }

    val panelWidth = max(panel.width, scaleData.width)
    val snapshot = sceneBuilder.buildSnapshot(panelWidth, panelHeight, scaleData, state.scaleMode, MinimapRegistry.isLegacy())
    panel.updateSnapshot(snapshot)
    updateDebugSnapshotStats(snapshot)
  }

  private fun updatePanelVisibility(minimapWidth: Int): Boolean {
    val hiddenForNarrowEditor = shouldHideForNarrowEditor(minimapWidth)
    val shouldBeVisible = !hiddenForNarrowEditor
    if (panel.isVisible == shouldBeVisible) return shouldBeVisible
    panel.isVisible = shouldBeVisible
    container.revalidate()
    container.repaint()
    return shouldBeVisible
  }

  private fun shouldHideForNarrowEditor(minimapWidth: Int): Boolean {
    if (minimapWidth <= 0) return false
    val editorWidth = container.width
    if (editorWidth <= 0) return false
    return editorWidth.toLong() <= minimapWidth.toLong() * HIDE_MINIMAP_EDITOR_WIDTH_MULTIPLIER
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
    structureUpdates.debounce(STRUCTURE_MARKERS_DEBOUNCE_MS).collect {
      updateStructureMarkersNow()
    }
  }

  private fun initDiagnosticsFlow() = scope.launch {
    diagnosticsUpdates.debounce(DIAGNOSTICS_DEBOUNCE_MS).collect {
      withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
        if (disposed) return@withContext
        refreshSnapshot()
        panel.repaint()
      }
    }
  }

  private fun updateDebugSnapshotStats(snapshot: MinimapSnapshot) {
    snapshotSequence++
    val tokenEntries = snapshot.tokenEntries.size
    val structureEntries = snapshot.structureEntries.size
    val diagnosticEntries = snapshot.diagnosticEntries.size
    val breakpointEntries = snapshot.breakpointEntries.size
    val foldEntries = snapshot.foldEntries.size
    val estimatedBytes = estimateSnapshotBytes(
      tokenEntries = tokenEntries,
      structureEntries = structureEntries,
      diagnosticEntries = diagnosticEntries,
      breakpointEntries = breakpointEntries,
      foldEntries = foldEntries,
    )

    val maxChanged = tokenEntries > maxTokenEntries ||
                     structureEntries > maxStructureEntries ||
                     diagnosticEntries > maxDiagnosticEntries ||
                     breakpointEntries > maxBreakpointEntries ||
                     foldEntries > maxFoldEntries ||
                     estimatedBytes > maxEstimatedSnapshotBytes

    if (tokenEntries > maxTokenEntries) maxTokenEntries = tokenEntries
    if (structureEntries > maxStructureEntries) maxStructureEntries = structureEntries
    if (diagnosticEntries > maxDiagnosticEntries) maxDiagnosticEntries = diagnosticEntries
    if (breakpointEntries > maxBreakpointEntries) maxBreakpointEntries = breakpointEntries
    if (foldEntries > maxFoldEntries) maxFoldEntries = foldEntries
    if (estimatedBytes > maxEstimatedSnapshotBytes) maxEstimatedSnapshotBytes = estimatedBytes

    if (!maxChanged && snapshotSequence > INITIAL_SNAPSHOT_DEBUG_LOGS && snapshotSequence % SNAPSHOT_DEBUG_LOG_PERIOD != 0L) {
      return
    }
  }

  private fun estimateSnapshotBytes(
    tokenEntries: Int,
    structureEntries: Int,
    diagnosticEntries: Int,
    breakpointEntries: Int,
    foldEntries: Int,
  ): Long {
    val renderEntries = tokenEntries + structureEntries
    return renderEntries * ESTIMATED_RENDER_ENTRY_BYTES +
           diagnosticEntries * ESTIMATED_DIAGNOSTIC_ENTRY_BYTES +
           breakpointEntries * ESTIMATED_BREAKPOINT_ENTRY_BYTES +
           foldEntries * ESTIMATED_FOLD_ENTRY_BYTES
  }

  companion object {
    private const val STRUCTURE_MARKERS_DEBOUNCE_MS: Long = 125
    private const val DIAGNOSTICS_DEBOUNCE_MS: Long = 125
    private const val HIDE_MINIMAP_EDITOR_WIDTH_MULTIPLIER: Long = 2
    private const val INITIAL_SNAPSHOT_DEBUG_LOGS: Long = 3
    private const val SNAPSHOT_DEBUG_LOG_PERIOD: Long = 200
    private const val ESTIMATED_RENDER_ENTRY_BYTES: Long = 96
    private const val ESTIMATED_DIAGNOSTIC_ENTRY_BYTES: Long = 64
    private const val ESTIMATED_BREAKPOINT_ENTRY_BYTES: Long = 64
    private const val ESTIMATED_FOLD_ENTRY_BYTES: Long = 64
  }
}
