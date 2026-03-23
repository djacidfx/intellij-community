// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.breakpoints

import com.intellij.ide.minimap.layout.MinimapLayoutMetrics
import com.intellij.ide.minimap.layout.MinimapLayoutMode
import com.intellij.ide.minimap.layout.MinimapLayoutUtil
import com.intellij.ide.minimap.render.MinimapRenderContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.MarkupIterator
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.ui.JBColor
import java.awt.Color

class MinimapBreakpointCollector(private val editor: Editor) {
  fun buildEntries(
    context: MinimapRenderContext,
    metrics: MinimapLayoutMetrics?,
    layoutMode: MinimapLayoutMode,
  ): List<MinimapBreakpointEntry> {
    if (layoutMode == MinimapLayoutMode.DENSE) return emptyList()

    val metrics = metrics ?: return emptyList()
    val editorEx = editor as? EditorEx ?: return emptyList()
    val document = editor.document
    val lineProjection = context.lineProjection
    val textLength = document.textLength
    if (textLength <= 0 || context.geometry.minimapHeight <= 0 || metrics.lineCount <= 0) return emptyList()

    val gutterWidth = metrics.contentStartX
    if (gutterWidth <= 0.0) return emptyList()

    val visibleOffsetRange = MinimapLayoutUtil.visibleOffsetRange(context, metrics, document) ?: return emptyList()
    val visibleStartOffset = visibleOffsetRange.startOffset
    val visibleEndOffset = visibleOffsetRange.endOffsetExclusive

    val entries = ArrayList<MinimapBreakpointEntry>()
    val processedLines = HashSet<Int>()
    MarkupIterator.mergeIterators(
      editorEx.markupModel.overlappingGutterIterator(visibleStartOffset, visibleEndOffset),
      editorEx.filteredDocumentMarkupModel.overlappingGutterIterator(visibleStartOffset, visibleEndOffset),
      RangeHighlighterEx.BY_AFFECTED_START_OFFSET,
    ).use { iterator ->
      while (iterator.hasNext() && entries.size < MAX_BREAKPOINT_ENTRIES) {
        val highlighter = iterator.next()
        if (!MinimapBreakpointUtil.isBreakpointHighlighter(highlighter)) continue

        val logicalLine = lineForHighlighter(highlighter, document, visibleStartOffset, visibleEndOffset) ?: continue
        if (lineProjection.isLineInCollapsedRegion(logicalLine)) continue
        val projectedLine = lineProjection.logicalToProjectedLine(logicalLine) ?: continue
        if (!processedLines.add(projectedLine)) continue

        val rect = MinimapLayoutUtil.rectFromDoubles(
          x1 = 0.0,
          x2 = gutterWidth,
          y1 = projectedLine * metrics.baseLineHeight,
          y2 = (projectedLine + 1) * metrics.baseLineHeight,
          areaStart = context.geometry.areaStart.toDouble(),
          maxWidth = gutterWidth,
        )
        entries.add(
          MinimapBreakpointEntry(
            projectedLine = projectedLine,
            rect2d = rect,
            color = colorFor(highlighter),
          ),
        )
      }
    }

    return entries
  }

  private fun lineForHighlighter(
    highlighter: RangeHighlighterEx,
    document: Document,
    visibleStartOffset: Int,
    visibleEndOffset: Int,
  ): Int? {
    if (visibleEndOffset <= visibleStartOffset) return null
    val startOffset = highlighter.startOffset
    val endOffset = highlighter.endOffset
    if (endOffset <= visibleStartOffset || startOffset >= visibleEndOffset) return null

    val clampedOffset = startOffset.coerceIn(visibleStartOffset, visibleEndOffset - 1)
    return document.getLineNumber(clampedOffset)
  }

  private fun colorFor(highlighter: RangeHighlighterEx): Color {
    val rendererClassName = highlighter.gutterIconRenderer?.javaClass?.simpleName.orEmpty()
    if (rendererClassName.contains("disabled", ignoreCase = true) ||
        rendererClassName.contains("muted", ignoreCase = true) ||
        rendererClassName.contains("inactive", ignoreCase = true)) {
      return INACTIVE_BREAKPOINT_COLOR
    }
    return ACTIVE_BREAKPOINT_COLOR
  }

  companion object {
    private const val MAX_BREAKPOINT_ENTRIES = 2_000
    private val ACTIVE_BREAKPOINT_COLOR = JBColor.RED
    private val INACTIVE_BREAKPOINT_COLOR = JBColor.GRAY
  }
}
