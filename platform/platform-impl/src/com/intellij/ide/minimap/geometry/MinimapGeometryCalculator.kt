// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.geometry

import com.intellij.ide.minimap.layout.MinimapLayoutUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
import kotlin.math.min

class MinimapGeometryCalculator(private val editor: Editor) {
  fun compute(panelHeight: Int, stateWidth: Int): MinimapGeometryData {
    val visibleArea = editor.scrollingModel.visibleArea
    val componentHeight = editor.contentComponent.height
    val rightMargin = MinimapLayoutUtil.getRightMarginChars(editor)
    val charWidth = EditorUtil.getPlainSpaceWidth(editor)

    val logicalWidth = MinimapLayoutUtil.computeLogicalWidth(rightMargin, charWidth, visibleArea.width)
    val minimapHeight = (componentHeight * stateWidth / logicalWidth.toDouble()).toInt().coerceAtLeast(0)

    val proportion = if (componentHeight > 0) minimapHeight.toDouble() / componentHeight else 0.0
    val thumbStart = (visibleArea.y * proportion).toInt()
    val thumbHeight = (visibleArea.height * proportion).toInt()

    val areaStart = if (minimapHeight > thumbHeight) {
      val maxScroll = (minimapHeight - thumbHeight).coerceAtLeast(1)
      val scrollPosition = thumbStart / maxScroll.toFloat()
      val panelSpan = (minimapHeight - panelHeight).coerceAtLeast(0)
      (scrollPosition * panelSpan).toInt().coerceAtLeast(0)
    }
    else {
      0
    }

    val areaEnd = areaStart + min(panelHeight, minimapHeight)

    return MinimapGeometryData(
      minimapHeight = minimapHeight,
      areaStart = areaStart,
      areaEnd = areaEnd,
      thumbStart = thumbStart,
      thumbHeight = thumbHeight
    )
  }
}
