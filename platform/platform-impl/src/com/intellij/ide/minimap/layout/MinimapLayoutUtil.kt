// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.layout

import com.intellij.ide.minimap.geometry.MinimapGeometryData
import com.intellij.ide.minimap.render.MinimapRenderContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import java.awt.geom.Rectangle2D

object MinimapLayoutUtil {
  fun lineTop(line: Int, baseLineHeight: Double): Double = line * baseLineHeight

  fun getLineGap(baseLineHeight: Double): Double = (baseLineHeight * 0.5).coerceAtMost(2.0)

  fun computeLayoutMetrics(editor: Editor, context: MinimapRenderContext): MinimapLayoutMetrics? {
    val document = editor.document
    val lineCount = document.lineCount
    if (lineCount <= 0) return null

    val baseLineHeight = context.geometry.minimapHeight.toDouble() / lineCount
    val rightMargin = getRightMarginChars(editor)
    val visibleWidth = editor.scrollingModel.visibleArea.width
    val charWidth = EditorUtil.getPlainSpaceWidth(editor)

    val logicalWidth = computeLogicalWidth(rightMargin, charWidth, visibleWidth)
    val scaleX = if (logicalWidth > 0) context.panelWidth / logicalWidth.toDouble() else 0.0

    val pxPerColumn = getPxPerColumn(context.panelWidth, rightMargin, charWidth, scaleX)
    if (pxPerColumn <= 0.0) return null
    return MinimapLayoutMetrics(lineCount, baseLineHeight, pxPerColumn)
  }

  fun computeLogicalWidth(rightMargin: Int, charWidth: Int, visibleWidth: Int): Int {
    return if (rightMargin > 0) rightMargin * charWidth else visibleWidth
  }

  fun visibleLines(geometry: MinimapGeometryData, lineCount: Int): IntRange {
    val lastLineIndex = (lineCount - 1).coerceAtLeast(0)
    val startLine = ((geometry.areaStart.toDouble() / geometry.minimapHeight) * lineCount)
      .toInt()
      .coerceIn(0, lastLineIndex)
    val endLineExclusive = ((geometry.areaEnd.toDouble() / geometry.minimapHeight) * lineCount)
      .toInt()
      .coerceIn(startLine + 1, lineCount)
    return startLine until endLineExclusive
  }

  fun lineBandRect(startLine: Int,
                   endLine: Int,
                   baseLineHeight: Double,
                   areaStart: Double): Rectangle2D.Double {
    val y1 = lineTop(startLine, baseLineHeight)
    val y2 = lineTop(endLine, baseLineHeight)

    val snapped = rectFromDoubles(0.0, 1.0, y1, y2, areaStart)
    val lineGap = getLineGap(baseLineHeight)
    val height = (snapped.height - lineGap).coerceAtLeast(1.0)
    val yOffset = snapped.y + lineGap / 2.0

    return Rectangle2D.Double(snapped.x, yOffset, snapped.width, height)
  }

  fun rectFromDoubles(x1: Double,
                      x2: Double,
                      y1: Double,
                      y2: Double,
                      areaStart: Double,
                      maxWidth: Double = Double.MAX_VALUE): Rectangle2D.Double {
    val sy1 = y1 - areaStart
    val sy2 = y2 - areaStart
    val clampedX1 = x1.coerceIn(0.0, maxWidth)
    val clampedX2 = x2.coerceIn(clampedX1, maxWidth)
    val width = (clampedX2 - clampedX1).coerceAtLeast(1.0)
    val height = (sy2 - sy1).coerceAtLeast(1.0)
    return Rectangle2D.Double(clampedX1, sy1, width, height)
  }

  fun getRightMarginChars(editor: Editor): Int {
    val project = editor.project ?: return -1
    return editor.settings.getRightMargin(project)
  }

  private fun getPxPerColumn(width: Int, rightMargin: Int, charWidth: Int, scaleX: Double): Double {
    return if (rightMargin > 0) {
      width / rightMargin.toDouble()
    } else {
      charWidth * scaleX
    }
  }
}
