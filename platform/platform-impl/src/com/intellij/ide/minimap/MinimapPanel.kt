// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap

import com.intellij.ide.minimap.legacy.MinimapLegacyPreview
import com.intellij.ide.minimap.geometry.MinimapScaleUtil
import com.intellij.ide.minimap.hover.MinimapHoverController
import com.intellij.ide.minimap.interaction.MinimapMouseInteractionController
import com.intellij.ide.minimap.paint.MinimapSelectionPainter
import com.intellij.ide.minimap.scene.MinimapSnapshot
import com.intellij.ide.minimap.render.MinimapRenderer
import com.intellij.ide.minimap.settings.MinimapSettings
import com.intellij.ide.minimap.settings.MinimapSettingsState
import com.intellij.ide.ui.customization.CustomActionsSchema
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.util.Disposer
import com.intellij.ui.Gray
import com.intellij.ui.PopupHandler
import kotlinx.coroutines.CoroutineScope
import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JPanel
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
    // Guard: hoverController is already disposed when removeNotify is triggered
    // as part of our own dispose() → container.remove(this) call.
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
      hoverController.paint(g2d)
    }

    minimapController.paintCaret(g2d)

    g2d.color = Gray._161
    val oldComposite = g2d.composite
    g2d.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f)
    g2d.fillRect(0, geometry.thumbStart - geometry.areaStart, width, geometry.thumbHeight)
    g2d.composite = oldComposite
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

    val visibleSpan = when {
      minimapHeight <= panelHeight -> (minimapHeight - thumbHeight)
      else -> (panelHeight - thumbHeight)
    }.coerceAtLeast(0)
    val desiredTop = (y - dragOffset).coerceIn(0, visibleSpan)

    val thumbStart = when {
      minimapHeight <= thumbHeight -> 0
      minimapHeight <= panelHeight -> desiredTop
      panelHeight <= thumbHeight -> 0
      else -> {
        val maxScroll = (minimapHeight - thumbHeight).toFloat()
        val denominator = (panelHeight - thumbHeight).toFloat()
        (desiredTop * maxScroll / denominator).roundToInt()
      }
    }

    val contentHeight = contentHeight()
    if (contentHeight <= 0) return
    val proportion = minimapHeight.toDouble() / contentHeight
    if (proportion <= 0.0) return

    editor.scrollingModel.scrollVertically((thumbStart / proportion).roundToInt())
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
