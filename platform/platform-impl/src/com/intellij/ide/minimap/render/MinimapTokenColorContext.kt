// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.render

import com.intellij.ide.minimap.layout.MinimapLayoutMetrics
import com.intellij.ide.minimap.render.MinimapRenderEntry
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import java.awt.Color
import java.util.HashMap

class MinimapTokenColorContext(
  renderContext: MinimapRenderContext,
  private val metrics: MinimapLayoutMetrics?,
) {
  private val areaStart = renderContext.geometry.areaStart.toDouble()
  private val document = renderContext.editor.document
  private val highlighter = renderContext.editor.highlighter

  private val scheme = renderContext.editor.colorsScheme
  private val defaultForeground = scheme.defaultForeground
  private val background = scheme.defaultBackground

  private val keyColorCache = HashMap<TextAttributesKey, Color?>()
  private val softenedColorsCache = HashMap<Color, JBColor>()

  fun colorFor(entry: MinimapRenderEntry): JBColor {
    val metrics = metrics ?: return JBColor.GRAY
    if (metrics.lineCount <= 0) return JBColor.GRAY

    val baseColor = entry.color ?: resolveEntryColor(entry, metrics)
    return softenedColorsCache.getOrPut(baseColor) {
      val softened = softenColor(baseColor, background)
      JBColor(softened, softened)
    }
  }

  private fun resolveEntryColor(entry: MinimapRenderEntry, metrics: MinimapLayoutMetrics): Color {
    val offset = entry.sampleOffset ?: offsetFromRect(entry, metrics) ?: return defaultForeground
    return colorAtOffset(offset)
  }

  private fun colorAtOffset(offset: Int): Color {
    val boundedOffset = offset.coerceIn(0, document.textLength)
    val iterator = highlighter.createIterator(boundedOffset)
    val keyColor = iterator.textAttributesKeys.firstNotNullOfOrNull { key ->
      keyColorCache.getOrPut(key) { scheme.getAttributes(key)?.foregroundColor }
    }
    return iterator.textAttributes?.foregroundColor ?: keyColor ?: defaultForeground
  }

  private fun offsetFromRect(entry: MinimapRenderEntry, metrics: MinimapLayoutMetrics): Int? {
    if (metrics.pxPerColumn <= 0.0 || metrics.baseLineHeight <= 0.0) return null

    val line = ((entry.rect2d.y + areaStart) / metrics.baseLineHeight).toInt().coerceIn(0, metrics.lineCount - 1)
    val column = (entry.rect2d.x / metrics.pxPerColumn).toInt().coerceAtLeast(0)
    val lineStart = document.getLineStartOffset(line)
    val lineEnd = document.getLineEndOffset(line)
    return (lineStart + column).coerceIn(lineStart, lineEnd)
  }

  private fun softenColor(color: Color, background: Color): Color {
    val brightness = (0.2126 * color.red + 0.7152 * color.green + 0.0722 * color.blue) / 255.0
    val balance = if (brightness < 0.2) 0.45 else 0.25
    return ColorUtil.mix(color, background, balance)
  }
}
