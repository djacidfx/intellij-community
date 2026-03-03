// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.interaction

import com.intellij.ide.minimap.MinimapPanel
import com.intellij.ide.minimap.MinimapPanel.Companion.MINIMUM_WIDTH
import com.intellij.ide.minimap.hover.MinimapHoverController
import com.intellij.ide.minimap.settings.MinimapSettings
import com.intellij.openapi.Disposable
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

class MinimapMouseInteractionController(
  private val panel: MinimapPanel,
  private val settings: MinimapSettings,
  private val hoverController: MinimapHoverController
) : MouseAdapter(), Disposable {
  private enum class MinimapMouseInteractionState { IDLE, RESIZING, DRAGGING }

  private val editor = panel.editor
  private var interactionState: MinimapMouseInteractionState = MinimapMouseInteractionState.IDLE
  private var resizeInitialX = 0
  private var resizeInitialWidth = 0
  private var dragAnimationDisabled = false

  fun install() {
    panel.addMouseListener(this)
    panel.addMouseWheelListener(this)
    panel.addMouseMotionListener(this)
  }

  override fun dispose() {
    panel.removeMouseListener(this)
    panel.removeMouseWheelListener(this)
    panel.removeMouseMotionListener(this)
  }

  override fun mousePressed(e: MouseEvent) {
    if (e.button != MouseEvent.BUTTON1) return

    if (interactionState == MinimapMouseInteractionState.IDLE && panel.isInResizeArea(e.x)) {
      interactionState = MinimapMouseInteractionState.RESIZING
      resizeInitialX = e.xOnScreen
      resizeInitialWidth = settings.state.width
    }
    else {
      interactionState = MinimapMouseInteractionState.DRAGGING
      dragAnimationDisabled = false
    }
  }

  override fun mouseReleased(e: MouseEvent) {
    if (e.button == MouseEvent.BUTTON1) {
      if (interactionState == MinimapMouseInteractionState.DRAGGING) {
        if (dragAnimationDisabled) {
          editor.scrollingModel.enableAnimation()
        }
      }
      interactionState = MinimapMouseInteractionState.IDLE
    }
  }

  override fun mouseWheelMoved(mouseWheelEvent: MouseWheelEvent) {
    editor.scrollingModel.scrollVertically(
      editor.scrollingModel.verticalScrollOffset +
        (mouseWheelEvent.preciseWheelRotation * editor.lineHeight * WHEEL_SCROLL_LINES).toInt())
  }

  override fun mouseDragged(e: MouseEvent) {
    if (interactionState == MinimapMouseInteractionState.RESIZING) {
      var newWidth = resizeInitialWidth + if (settings.state.rightAligned) resizeInitialX - e.xOnScreen else e.xOnScreen - resizeInitialX

      newWidth = when {
        newWidth < MINIMUM_WIDTH -> MINIMUM_WIDTH
        newWidth > panel.container.width / 2 -> panel.container.width / 2
        else -> newWidth
      }

      if (settings.state.width != newWidth) {
        settings.state.width = newWidth
        settings.settingsChangeCallback.notify(MinimapSettings.SettingsChangeType.Normal)
      }
    }
    else if (interactionState == MinimapMouseInteractionState.DRAGGING) {
      if (!dragAnimationDisabled) {
        editor.scrollingModel.disableAnimation()
        dragAnimationDisabled = true
      }
      panel.scrollTo(e.y)
    }
  }

  override fun mouseClicked(e: MouseEvent) {
    if (e.button != MouseEvent.BUTTON1) return
    if (panel.isInResizeArea(e.x)) return
    handleClick(e)
  }

  override fun mouseMoved(e: MouseEvent) {
    if (panel.isInResizeArea(e.x)) {
      panel.cursor = if (settings.state.rightAligned) Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
      else Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
    }
    else {
      panel.cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
    }
    updateHover(e.point)
  }

  override fun mouseExited(e: MouseEvent) {
    updateHover(null)
  }

  private fun updateHover(point: Point?) {
    hoverController.updateHover(point)
  }

  private fun handleClick(e: MouseEvent) {
    // TODO: if clicked on structure view element -> scroll to the element
    panel.scrollTo(e.y)
  }

  companion object {
    private const val WHEEL_SCROLL_LINES: Int = 5
  }
}
