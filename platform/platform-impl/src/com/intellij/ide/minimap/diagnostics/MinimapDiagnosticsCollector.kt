// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.diagnostics

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.ide.minimap.layout.MinimapLayoutMetrics
import com.intellij.ide.minimap.layout.MinimapLayoutUtil
import com.intellij.ide.minimap.render.MinimapRenderContext
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.MarkupIterator
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.HighlighterTargetArea

class MinimapDiagnosticsCollector(private val editor: Editor) {
  fun buildEntries(context: MinimapRenderContext, metrics: MinimapLayoutMetrics?): List<MinimapDiagnosticEntry> {
    val metrics = metrics ?: return emptyList()
    val editorEx = editor as? EditorEx ?: return emptyList()
    val document = editor.document
    val textLength = document.textLength
    if (textLength <= 0 || context.panelWidth <= 0 || context.geometry.minimapHeight <= 0 || metrics.lineCount <= 0) {
      return emptyList()
    }

    val visibleLines = MinimapLayoutUtil.visibleLines(context.geometry, metrics.lineCount)
    if (visibleLines.isEmpty()) return emptyList()

    val visibleStartOffset = document.getLineStartOffset(visibleLines.first)
    val visibleEndOffset = if (visibleLines.last + 1 < metrics.lineCount) {
      document.getLineStartOffset(visibleLines.last + 1)
    }
    else {
      textLength
    }
    if (visibleEndOffset <= visibleStartOffset) return emptyList()

    val entries = ArrayList<MinimapDiagnosticEntry>()
    MarkupIterator.mergeIterators(
      editorEx.markupModel.overlappingErrorStripeIterator(visibleStartOffset, visibleEndOffset),
      editorEx.filteredDocumentMarkupModel.overlappingErrorStripeIterator(visibleStartOffset, visibleEndOffset),
      RangeHighlighterEx.BY_AFFECTED_START_OFFSET,
    ).use { iterator ->
      while (iterator.hasNext() && entries.size < MAX_DIAGNOSTIC_ENTRIES) {
        val highlighter = iterator.next()
        if (!highlighter.isValid) continue

        val severity = severityFor(highlighter) ?: continue
        appendHighlighterEntries(
          entries = entries,
          highlighter = highlighter,
          severity = severity,
          context = context,
          metrics = metrics,
          document = document,
          textLength = textLength,
          visibleStartOffset = visibleStartOffset,
          visibleEndOffset = visibleEndOffset,
        )
      }
    }

    return entries
  }

  private fun severityFor(highlighter: RangeHighlighterEx): MinimapDiagnosticSeverity? {
    val info = HighlightInfo.fromRangeHighlighter(highlighter) ?: return null
    val severity = info.severity
    return when {
      severity == HighlightSeverity.ERROR -> MinimapDiagnosticSeverity.ERROR
      severity.myVal >= HighlightSeverity.WARNING.myVal -> MinimapDiagnosticSeverity.WARNING
      else -> null
    }
  }

  private fun appendHighlighterEntries(
    entries: MutableList<MinimapDiagnosticEntry>,
    highlighter: RangeHighlighterEx,
    severity: MinimapDiagnosticSeverity,
    context: MinimapRenderContext,
    metrics: MinimapLayoutMetrics,
    document: Document,
    textLength: Int,
    visibleStartOffset: Int,
    visibleEndOffset: Int,
  ) {
    val startOffset = highlighter.startOffset.coerceIn(visibleStartOffset, visibleEndOffset)
    val rawEndOffset = highlighter.endOffset.coerceIn(startOffset, visibleEndOffset)
    val endOffsetExclusive = if (rawEndOffset > startOffset) rawEndOffset else (startOffset + 1).coerceAtMost(visibleEndOffset)
    if (endOffsetExclusive <= startOffset) return

    val startLine = document.getLineNumber(startOffset)
    val endLine = document.getLineNumber((endOffsetExclusive - 1).coerceAtLeast(startOffset))

    for (line in startLine..endLine) {
      if (entries.size >= MAX_DIAGNOSTIC_ENTRIES) return

      val lineStartOffset = document.getLineStartOffset(line)
      val lineEndOffset = document.getLineEndOffset(line)
      val segmentStart = if (line == startLine) startOffset else lineStartOffset
      var segmentEndExclusive = if (line == endLine) endOffsetExclusive else lineEndOffset
      if (segmentEndExclusive <= segmentStart) {
        segmentEndExclusive = (segmentStart + 1).coerceAtMost(textLength)
      }
      if (segmentEndExclusive <= segmentStart) continue

      val rect = lineRect(
        highlighter = highlighter,
        context = context,
        metrics = metrics,
        line = line,
        lineStartOffset = lineStartOffset,
        segmentStart = segmentStart,
        segmentEndExclusive = segmentEndExclusive,
      )
      entries.add(MinimapDiagnosticEntry(rect, severity))
    }
  }

  private fun lineRect(
    highlighter: RangeHighlighterEx,
    context: MinimapRenderContext,
    metrics: MinimapLayoutMetrics,
    line: Int,
    lineStartOffset: Int,
    segmentStart: Int,
    segmentEndExclusive: Int,
  ) = if (highlighter.targetArea == HighlighterTargetArea.LINES_IN_RANGE || metrics.pxPerColumn <= 0.0) {
    MinimapLayoutUtil.rectFromDoubles(
      x1 = 0.0,
      x2 = context.panelWidth.toDouble(),
      y1 = line * metrics.baseLineHeight,
      y2 = (line + 1) * metrics.baseLineHeight,
      areaStart = context.geometry.areaStart.toDouble(),
      maxWidth = context.panelWidth.toDouble(),
    )
  }
  else {
    val startColumn = (segmentStart - lineStartOffset).coerceAtLeast(0)
    val endColumn = (segmentEndExclusive - lineStartOffset).coerceAtLeast(startColumn + 1)
    MinimapLayoutUtil.rectFromDoubles(
      x1 = startColumn * metrics.pxPerColumn,
      x2 = endColumn * metrics.pxPerColumn,
      y1 = line * metrics.baseLineHeight,
      y2 = (line + 1) * metrics.baseLineHeight,
      areaStart = context.geometry.areaStart.toDouble(),
      maxWidth = context.panelWidth.toDouble(),
    )
  }

  companion object {
    private const val MAX_DIAGNOSTIC_ENTRIES = 4_000
  }
}
