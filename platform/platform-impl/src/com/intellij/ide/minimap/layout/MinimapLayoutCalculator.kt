// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.layout

import com.intellij.ide.minimap.model.MinimapStructureMarker
import com.intellij.ide.minimap.render.MinimapRenderContext
import com.intellij.ide.minimap.render.MinimapRenderEntry
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import java.awt.geom.Rectangle2D
import kotlin.math.ceil

class MinimapLayoutCalculator(private val editor: Editor) {
  private data class LineBand(
    val yOffset: Double,
    val height: Double,
  )

  private data class PreparedLayout(
    val layout: MinimapLayoutContext,
    val documentLength: Int,
    val result: ArrayList<MinimapRenderEntry>,
  )

  fun buildLayout(
    context: MinimapRenderContext,
    structureMarkers: List<MinimapStructureMarker>,
    mode: MinimapLayoutMode,
  ): List<MinimapRenderEntry> {
    return when (mode) {
      MinimapLayoutMode.EXACT -> buildExactLayout(context, structureMarkers)
      MinimapLayoutMode.DENSE -> buildDenseLayout(context, structureMarkers)
    }
  }

  private fun buildExactLayout(
    context: MinimapRenderContext,
    structureMarkers: List<MinimapStructureMarker>,
  ): List<MinimapRenderEntry> {
    val prepared = prepareLayout(context, structureMarkers) ?: return emptyList()
    appendTokenFillers(prepared.result, prepared.layout)
    appendStructureMarkers(prepared.result, prepared.layout, structureMarkers, prepared.documentLength)
    return prepared.result
  }

  private fun buildDenseLayout(
    context: MinimapRenderContext,
    structureMarkers: List<MinimapStructureMarker>,
  ): List<MinimapRenderEntry> {
    val prepared = prepareLayout(context, structureMarkers) ?: return emptyList()
    appendDenseFillers(prepared.result, prepared.layout)
    appendStructureMarkers(prepared.result, prepared.layout, structureMarkers, prepared.documentLength)
    return prepared.result
  }

  private fun prepareLayout(
    context: MinimapRenderContext,
    structureMarkers: List<MinimapStructureMarker>,
  ): PreparedLayout? {
    val geometry = context.geometry
    val panelWidth = context.panelWidth
    val minimapHeight = geometry.minimapHeight

    if (panelWidth <= 0 || minimapHeight <= 0) return null

    val document = editor.document
    val documentLength = document.textLength
    if (documentLength == 0) return null

    val metrics = MinimapLayoutUtil.computeLayoutMetrics(editor, context) ?: return null
    val lineCount = metrics.lineCount
    if (lineCount == 0) return null

    val result = ArrayList<MinimapRenderEntry>(structureMarkers.size)
    val visibleLines = MinimapLayoutUtil.visibleLines(geometry, lineCount)
    val layout = MinimapLayoutContext(document, metrics, panelWidth, geometry.areaStart.toDouble(), visibleLines)
    return PreparedLayout(layout, documentLength, result)
  }

  private fun appendTokenFillers(result: MutableList<MinimapRenderEntry>,
                                 context: MinimapLayoutContext) {
    val pxPerColumn = context.metrics.pxPerColumn
    val document = context.document
    val chars = document.charsSequence
    if (context.visibleLines.isEmpty()) return
    val iterator = editor.highlighter.createIterator(document.getLineStartOffset(context.visibleLines.first))

    for (line in context.visibleLines) {
      val lineStartOffset = document.getLineStartOffset(line)
      val lineEndOffset = document.getLineEndOffset(line)
      while (!iterator.atEnd() && iterator.end <= lineStartOffset) {
        iterator.advance()
      }
      if (lineEndOffset <= lineStartOffset) continue

      val trimmedEndOffset = trimLineEnd(chars, lineStartOffset, lineEndOffset)
      if (trimmedEndOffset <= lineStartOffset) continue

      val band = getLineBand(line, line + 1, context) ?: continue

      while (!iterator.atEnd() && iterator.start < trimmedEndOffset) {
        val tokenStart = iterator.start.coerceAtLeast(lineStartOffset)
        val tokenEnd = iterator.end.coerceAtMost(trimmedEndOffset)

        if (tokenEnd > tokenStart && !isWhitespace(chars, tokenStart, tokenEnd)) {
          val startColumn = (tokenStart - lineStartOffset).coerceAtLeast(0)
          val endColumn = (tokenEnd - lineStartOffset).coerceAtLeast(startColumn + 1)
          val rect2d = rectForColumns(startColumn, endColumn, band, context, pxPerColumn)
          result.add(MinimapRenderEntry(null, rect2d, sampleOffset = tokenStart))
        }

        if (iterator.end <= trimmedEndOffset) {
          iterator.advance()
        }
        else {
          break
        }
      }
    }
  }

  private fun appendDenseFillers(result: MutableList<MinimapRenderEntry>,
                                 context: MinimapLayoutContext) {
    val visibleLines = context.visibleLines
    if (visibleLines.isEmpty()) return

    val pxPerColumn = context.metrics.pxPerColumn
    val baseLineHeight = context.metrics.baseLineHeight
    if (baseLineHeight <= 0.0) return

    val linesPerPixel = 1.0 / baseLineHeight
    val lineStride = ceil(linesPerPixel).toInt().coerceAtLeast(1)
    val document = context.document
    val chars = document.charsSequence

    var line = visibleLines.first
    val endLineExclusive = visibleLines.last + 1
    while (line < endLineExclusive) {
      val bandEndLine = (line + lineStride).coerceAtMost(endLineExclusive)
      val band = getLineBand(line, bandEndLine, context)
      if (band != null) {
        var sampleLine = line
        var lineStartOffset = 0
        var trimmedStartOffset = 0
        var trimmedEndOffset = 0
        var hasSample = false

        while (sampleLine < bandEndLine) {
          lineStartOffset = document.getLineStartOffset(sampleLine)
          val lineEndOffset = document.getLineEndOffset(sampleLine)
          if (lineEndOffset > lineStartOffset) {
            trimmedEndOffset = trimLineEnd(chars, lineStartOffset, lineEndOffset)
            if (trimmedEndOffset > lineStartOffset) {
              trimmedStartOffset = trimLineStart(chars, lineStartOffset, trimmedEndOffset)
              if (trimmedStartOffset < trimmedEndOffset) {
                hasSample = true
                break
              }
            }
          }
          sampleLine++
        }

        if (hasSample) {
          val startColumn = (trimmedStartOffset - lineStartOffset).coerceAtLeast(0)
          val endColumn = (trimmedEndOffset - lineStartOffset).coerceAtLeast(startColumn + 1)
          val rect2d = rectForColumns(startColumn, endColumn, band, context, pxPerColumn)
          result.add(MinimapRenderEntry(null, rect2d, sampleOffset = trimmedStartOffset))
        }
      }
      line = bandEndLine
    }
  }

  private fun appendStructureMarkers(result: MutableList<MinimapRenderEntry>,
                                     context: MinimapLayoutContext,
                                     structureMarkers: List<MinimapStructureMarker>,
                                     documentLength: Int) {
    // todo: some logic can be shared with appendTokenFillers
    if (structureMarkers.isEmpty()) return

    val document = context.document
    val pxPerColumn = context.metrics.pxPerColumn
    val lineCount = context.metrics.lineCount

    for (marker in structureMarkers) {
      val range = resolveRange(marker) ?: continue

      val startOffset = range.startOffset.coerceIn(0, documentLength)
      val endOffset = range.endOffset.coerceIn(startOffset, documentLength)
      val startLine = document.getLineNumber(startOffset)
      val endLine = (startLine + 1).coerceAtMost(lineCount)
      val band = getLineBand(startLine, endLine, context) ?: continue

      val lineStartOffset = document.getLineStartOffset(startLine)
      val lineEndOffset = document.getLineEndOffset(startLine)
      val endOffsetInLine = endOffset.coerceIn(startOffset, lineEndOffset)
      val startColumn = (startOffset - lineStartOffset).coerceAtLeast(0)
      val endColumn = (endOffsetInLine - lineStartOffset).coerceAtLeast(startColumn + 1)
      val rect2d = rectForColumns(startColumn, endColumn, band, context, pxPerColumn)
      result.add(MinimapRenderEntry(marker.element, rect2d, sampleOffset = startOffset))
    }
  }

  private fun getLineBand(startLine: Int, endLine: Int, context: MinimapLayoutContext): LineBand? {
    val band = MinimapLayoutUtil.lineBandRect(startLine, endLine, context.metrics.baseLineHeight, context.areaStart)
    return if (band.height <= 0.0) null else LineBand(band.y, band.height)
  }

  private fun getRectForLineBand(x1: Double, x2: Double, band: LineBand, context: MinimapLayoutContext): Rectangle2D.Double {
    val maxWidth = context.panelWidth.toDouble()
    val clampedX1 = x1.coerceIn(0.0, maxWidth)
    val clampedX2 = x2.coerceIn(clampedX1, maxWidth)
    val width = (clampedX2 - clampedX1).coerceAtLeast(1.0)

    return Rectangle2D.Double(clampedX1, band.yOffset, width, band.height)
  }

  private fun rectForColumns(startColumn: Int,
                             endColumn: Int,
                             band: LineBand,
                             context: MinimapLayoutContext,
                             perChar: Double): Rectangle2D.Double {
    return if (perChar < 0) {
      getRectForLineBand(0.0, context.panelWidth.toDouble(), band, context)
    } else {
      getRectForLineBand(startColumn * perChar, endColumn * perChar, band, context)
    }
  }

  private fun resolveRange(structureMarker: MinimapStructureMarker): TextRange? {
    val pointerRange = structureMarker.pointer?.range
    if (pointerRange != null) return TextRange(pointerRange.startOffset, pointerRange.endOffset)

    val rangeMarker = structureMarker.rangeMarker ?: return null
    if (!rangeMarker.isValid) return null

    return rangeMarker.textRange
  }

  // TODO: definitely there must be a platform solution for such ops
  private fun trimLineEnd(chars: CharSequence, start: Int, end: Int): Int {
    var index = end
    while (index > start && chars[index - 1].isWhitespace()) index--
    return index
  }

  private fun trimLineStart(chars: CharSequence, start: Int, end: Int): Int {
    var index = start
    while (index < end && chars[index].isWhitespace()) index++
    return index
  }

  private fun isWhitespace(chars: CharSequence, start: Int, end: Int): Boolean {
    for (index in start until end) {
      if (!chars[index].isWhitespace()) return false
    }
    return true
  }
}
