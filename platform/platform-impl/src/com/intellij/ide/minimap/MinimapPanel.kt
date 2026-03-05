// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap

import com.intellij.ide.minimap.legacy.MinimapLegacyPreview
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
import com.intellij.openapi.util.Disposer
import com.intellij.ui.Gray
import com.intellij.ui.PopupHandler
import kotlinx.coroutines.CoroutineScope
import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JPanel

class MinimapPanel(
  private val parentDisposable: Disposable,
  coroutineScope: CoroutineScope,
  val editor: Editor,
  val container: JPanel,
) : JPanel() {
  private val renderer = MinimapRenderer()

  val settings: MinimapSettings = MinimapSettings.getInstance()

  private var settingsState: MinimapSettingsState = settings.state

  private val selectionPainter = MinimapSelectionPainter(editor)

  private val legacyPreview = MinimapLegacyPreview { repaint() }

  private var snapshot: MinimapSnapshot? = null

  private var initialized = false

  private val minimapController = registerDisposable(
    MinimapController(
      coroutineScope,
      this,
      container,
    )
  )

  private val hoverController = registerDisposable(
    MinimapHoverController(
      coroutineScope,
      this,
      minimapController::isDocumentCommitted
    )
  )

  private val interactionController = registerDisposable(
    MinimapMouseInteractionController(
      this,
      hoverController
    )
  )

  private val onSettingsChange = { _: MinimapSettings.SettingsChangeType ->
    updatePreferredSize()
    revalidate()
    minimapController.refreshSnapshot()
    repaint()
  }

  init {
    PopupHandler.installPopupMenu(this, createPopupActionGroup(), "MinimapPopup")
    installSettingsListeners()
    updatePreferredSize()

    if (!MinimapRegistry.isLegacy()) {
      minimapController.updateStructureMarkersNow()
    }

    minimapController.install()
    interactionController.install()
  }

  override fun addNotify() {
    super.addNotify()
  }

  override fun removeNotify() {
    hoverController.hideBalloon()
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
      legacyPreview.paint(g2d, editor, settingsState.width, snapshot.geometry)
    }
    else {
      renderer.paint(g2d, snapshot.context, snapshot.entries)
      hoverController.paint(g2d)
      selectionPainter.paint(g2d, snapshot.context, snapshot.entries)
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

  fun onClose() {
    uninstallSettingsListeners()
    legacyPreview.clear()
  }

  fun scrollTo(y: Int) {
    val geometry = currentSnapshot()?.geometry ?: return
    val percentage = (y + geometry.areaStart) / geometry.minimapHeight.toFloat()
    val offset = editor.component.size.height / 2

    editor.scrollingModel.scrollVertically((percentage * editor.contentComponent.size.height - offset).toInt())
  }

  fun currentSnapshot(): MinimapSnapshot? = snapshot

  fun updateSnapshot(snapshot: MinimapSnapshot) {
    this.snapshot = snapshot
    hoverController.onSnapshot(snapshot)
  }

  private fun updatePreferredSize() {
    preferredSize = Dimension(settingsState.width, 0)
  }


  private fun installSettingsListeners() {
    settings.settingsChangeCallback += onSettingsChange
  }

  private fun uninstallSettingsListeners() {
    settings.settingsChangeCallback -= onSettingsChange
  }


  private fun <T : Disposable> registerDisposable(disposable: T): T {
    Disposer.register(parentDisposable, disposable)
    return disposable
  }

  private fun createPopupActionGroup() = CustomActionsSchema.getInstance().getCorrectedAction("MinimapActionsGroup") as? ActionGroup
                                         ?: DefaultActionGroup()

}
