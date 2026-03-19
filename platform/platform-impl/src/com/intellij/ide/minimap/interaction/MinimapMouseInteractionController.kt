// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.interaction

import com.intellij.ide.minimap.MinimapPanel
import com.intellij.ide.minimap.hover.MinimapHoverController
import com.intellij.openapi.Disposable
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

class MinimapMouseInteractionController(
  private val panel: MinimapPanel,
  private val hoverController: MinimapHoverController
) : MouseAdapter(), Disposable {
  private enum class MinimapMouseInteractionState { IDLE, DRAGGING }

  private val editor = panel.editor
  private var interactionState: MinimapMouseInteractionState = MinimapMouseInteractionState.IDLE
  private var dragAnimationDisabled = false
  private var dragOffset = 0

  fun install() {
    panel.addMouseListener(this)
    panel.addMouseWheelListener(this)
    panel.addMouseMotionListener(this)
  }

  override fun dispose() {
    if (dragAnimationDisabled) {
      editor.scrollingModel.enableAnimation()
      dragAnimationDisabled = false
    }

    interactionState = MinimapMouseInteractionState.IDLE
    dragOffset = 0

    panel.removeMouseListener(this)
    panel.removeMouseWheelListener(this)
    panel.removeMouseMotionListener(this)
  }

  override fun mousePressed(e: MouseEvent) {
    if (e.button != MouseEvent.BUTTON1) return

    val geometry = panel.currentSnapshot()?.geometry
    if (geometry == null || geometry.thumbHeight <= 0 || geometry.minimapHeight <= 0) {
      interactionState = MinimapMouseInteractionState.IDLE
      dragOffset = 0
      return
    }

    interactionState = MinimapMouseInteractionState.DRAGGING
    dragAnimationDisabled = false

    val thumbTop = geometry.thumbStart - geometry.areaStart
    val thumbBottom = thumbTop + geometry.thumbHeight

    dragOffset = if (e.y in thumbTop until thumbBottom) {
      e.y - thumbTop
    }
    else {
      geometry.thumbHeight / 2
    }
  }

  override fun mouseReleased(e: MouseEvent) {
    if (e.button != MouseEvent.BUTTON1) return

    if (dragAnimationDisabled) {
      editor.scrollingModel.enableAnimation()
      dragAnimationDisabled = false
    }

    interactionState = MinimapMouseInteractionState.IDLE
    dragOffset = 0
  }

  override fun mouseWheelMoved(mouseWheelEvent: MouseWheelEvent) {
    editor.scrollingModel.scrollVertically(
      editor.scrollingModel.verticalScrollOffset +
        (mouseWheelEvent.preciseWheelRotation * editor.lineHeight * WHEEL_SCROLL_LINES).toInt())
  }

  override fun mouseDragged(e: MouseEvent) {
    if (interactionState != MinimapMouseInteractionState.DRAGGING) return

    if (!dragAnimationDisabled) {
      editor.scrollingModel.disableAnimation()
      dragAnimationDisabled = true
    }

    panel.scrollThumbTo(e.y, dragOffset)
  }

  override fun mouseClicked(e: MouseEvent) {
    if (e.button != MouseEvent.BUTTON1) return
    handleClick(e)
  }

  override fun mouseMoved(e: MouseEvent) {
    panel.cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
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
