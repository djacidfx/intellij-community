// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.hover

import com.intellij.ide.minimap.MinimapPanel
import com.intellij.ide.minimap.render.MinimapRenderContext
import java.awt.Graphics2D
import kotlin.math.roundToInt

class MinimapHoverPresenter(private val panel: MinimapPanel) {
  private val hoverPainter = MinimapHoverPainter()
  private val balloonController = MinimapBalloonController(panel)
  private var activeTarget: MinimapHoverTarget? = null
  private var lastContext: MinimapRenderContext? = null

  fun setContext(context: MinimapRenderContext?) {
    lastContext = context
  }

  fun setTarget(target: MinimapHoverTarget?) {
    activeTarget = target
    if (target == null) {
      balloonController.hide()
      return
    }
    balloonController.show(target.text, target.rect, target.icon)
  }

  fun paint(graphics: Graphics2D) {
    val target = activeTarget ?: return
    val context = lastContext ?: return

    val lineHeight = computeLineHeight(context)
    hoverPainter.paint(graphics, target.rect, lineHeight)
  }

  fun hide() {
    activeTarget = null
    balloonController.hide()
  }

  private fun computeLineHeight(context: MinimapRenderContext): Int {
    val lineCount = panel.editor.document.lineCount
    if (lineCount <= 0) return 1
    val baseLineHeight = context.geometry.minimapHeight.toDouble() / lineCount
    val lineGap = (baseLineHeight * 0.5).coerceAtMost(2.0)

    return (baseLineHeight - lineGap).roundToInt().coerceAtLeast(1)
  }
}
