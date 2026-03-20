// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap

import com.intellij.ide.minimap.legacy.MinimapLegacyPreview
import com.intellij.ide.minimap.geometry.MinimapScaleUtil
import com.intellij.ide.minimap.hover.MinimapHoverController
import com.intellij.ide.minimap.interaction.MinimapMouseInteractionController
import com.intellij.ide.minimap.diagnostics.MinimapDiagnosticsPainter
import com.intellij.ide.minimap.paint.MinimapSelectionPainter
import com.intellij.ide.minimap.scene.MinimapSnapshot
import com.intellij.ide.minimap.render.MinimapRenderer
import com.intellij.ide.minimap.settings.MinimapSettings
import com.intellij.ide.minimap.settings.MinimapSettingsState
import com.intellij.ide.minimap.thumb.MinimapThumb
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.Disposer
import com.intellij.ui.PopupHandler
import kotlinx.coroutines.CoroutineScope
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JPanel
import kotlin.math.min
import kotlin.math.roundToInt

class MinimapPanel(
  coroutineScope: CoroutineScope,
  val editor: Editor,
  val container: JPanel,
) : JPanel(), Disposable {
  private val renderer = MinimapRenderer()

  val settings: MinimapSettings = MinimapSettings.getInstance()

  private val settingsState: MinimapSettingsState
    get() = settings.state

  private val selectionPainter = MinimapSelectionPainter(editor)
  private val diagnosticsPainter = MinimapDiagnosticsPainter(editor)

  private val legacyPreview = MinimapLegacyPreview { repaint() }

  private var snapshot: MinimapSnapshot? = null

  private var initialized = false

  @Volatile
  private var disposed = false

  private val minimapController = MinimapController(
    coroutineScope,
    this,
    container,
  ).also { Disposer.register(this, it) }

  private val hoverController = MinimapHoverController(
    coroutineScope,
    this,
    minimapController::isDocumentCommitted,
  ).also { Disposer.register(this, it) }

  private val interactionController = MinimapMouseInteractionController(
    this,
    hoverController,
  ).also { Disposer.register(this, it) }

  private val onSettingsChange = { _: MinimapSettings.SettingsChangeType ->
    updatePreferredSize()
    revalidate()
    minimapController.refreshSnapshot()
    repaint()
  }

  init {
    // Tie the panel's lifetime to the editor: when the editor is closed,
    // this panel (and all its children) are disposed automatically.
    EditorUtil.disposeWithEditor(editor, this)

    PopupHandler.installPopupMenu(this, createPopupActionGroup(), "MinimapPopup")
    installSettingsListeners()
    updatePreferredSize()

    if (!MinimapRegistry.isLegacy()) {
      minimapController.updateStructureMarkersNow()
    }

    minimapController.install()
    interactionController.install()
  }

  // Called by Disposer after all children (controllers) have been disposed.
  // Settings listener is removed here — not earlier — so it cannot fire
  // after the panel is gone but before the controllers are cleaned up.
  override fun dispose() {
    disposed = true
    uninstallSettingsListeners()
    legacyPreview.clear()
    snapshot = null
    container.remove(this)
    container.revalidate()
    container.repaint()
  }

  fun onClose() {
    if (!disposed) {
      Disposer.dispose(this)
    }
  }

  override fun removeNotify() {
    if (!disposed) {
      hoverController.hideBalloon()
    }
    super.removeNotify()
  }

  override fun paint(g: Graphics) {
    if (!initialized) {
      minimapController.refreshSnapshot()
      initialized = true
    }

    val g2d = g as Graphics2D
    g2d.color = editor.contentComponent.background
    g2d.fillRect(0, 0, width, height)

    val snapshot = currentSnapshot() ?: return
    val geometry = snapshot.geometry

    if (MinimapRegistry.isLegacy()) {
      legacyPreview.paint(g2d, editor, width, snapshot.geometry)
    }
    else {
      renderer.paint(g2d, snapshot.context, snapshot.tokenEntries, snapshot.layoutMetrics)
      selectionPainter.paint(g2d, snapshot.context, snapshot.layoutMetrics)
      diagnosticsPainter.paint(g2d, snapshot.diagnosticEntries)
      hoverController.paint(g2d)
    }

    minimapController.paintCaret(g2d)
    MinimapThumb.paint(g2d, width, geometry)
  }

  override fun updateUI() {
    super.updateUI()

    if (initialized && MinimapRegistry.isLegacy()) {
      legacyPreview.update(editor, currentSnapshot()?.geometry?.minimapHeight ?: 0, true)
    }
  }

  fun scrollTo(y: Int) {
    val geometry = currentSnapshot()?.geometry ?: return
    val minimapHeight = geometry.minimapHeight
    if (minimapHeight <= 0) return

    val contentHeight = editor.contentComponent.size.height
    if (contentHeight <= 0) return

    val areaY = (y + geometry.areaStart).coerceIn(0, minimapHeight)
    val percentage = areaY.toDouble() / minimapHeight.toDouble()
    val viewportHalf = editor.component.size.height / 2
    editor.scrollingModel.scrollVertically((percentage * contentHeight - viewportHalf).roundToInt())
  }

  fun scrollThumbTo(y: Int, dragOffset: Int) {
    val geometry = currentSnapshot()?.geometry ?: return
    val panelHeight = height
    val minimapHeight = geometry.minimapHeight
    val thumbHeight = geometry.thumbHeight
    if (panelHeight <= 0 || minimapHeight <= 0 || thumbHeight <= 0) return

    val thumbStart = MinimapThumb.computeStartFromDrag(y, dragOffset, panelHeight, minimapHeight, thumbHeight)

    val contentHeight = contentHeight()
    if (contentHeight <= 0) return
    val visibleHeight = min(editor.scrollingModel.visibleArea.height, contentHeight).coerceAtLeast(0)
    val scrollRange = (contentHeight - visibleHeight).coerceAtLeast(0)
    val targetScrollOffset = MinimapThumb.mapThumbStartToScrollOffset(thumbStart, scrollRange, minimapHeight, thumbHeight)
    editor.scrollingModel.scrollVertically(targetScrollOffset)
  }

  fun currentSnapshot(): MinimapSnapshot? = snapshot

  fun updateSnapshot(snapshot: MinimapSnapshot) {
    this.snapshot = snapshot
    hoverController.onSnapshot(snapshot)
  }

  internal fun updatePreferredWidth(preferredWidth: Int): Boolean {
    if (preferredSize.width == preferredWidth) return false
    preferredSize = Dimension(preferredWidth, 0)
    return true
  }

  private fun updatePreferredSize() {
    val panelHeight = if (height > 0) height else container.height
    val preferredWidth = MinimapScaleUtil.effectiveWidth(editor, panelHeight, settingsState.width, settingsState.scaleMode)
    updatePreferredWidth(preferredWidth)
  }

  private fun contentHeight(): Int {
    return MinimapScaleUtil.contentHeight(editor, settingsState.scaleMode)
  }

  private fun installSettingsListeners() {
    settings.settingsChangeCallback += onSettingsChange
  }

  private fun uninstallSettingsListeners() {
    settings.settingsChangeCallback -= onSettingsChange
  }

  private fun createPopupActionGroup() = CustomActionsSchema.getInstance().getCorrectedAction("MinimapActionsGroup") as? ActionGroup
                                         ?: DefaultActionGroup()
}
