// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.scene

import com.intellij.ide.minimap.geometry.MinimapGeometryCalculator
import com.intellij.ide.minimap.geometry.MinimapScaleData
import com.intellij.ide.minimap.layout.MinimapLayoutCalculator
import com.intellij.ide.minimap.layout.MinimapLayoutMode
import com.intellij.ide.minimap.layout.MinimapLayoutModeSelector
import com.intellij.ide.minimap.model.MinimapStructureMarker
import com.intellij.ide.minimap.model.MinimapModel
import com.intellij.ide.minimap.render.MinimapRenderContext
import com.intellij.ide.minimap.settings.MinimapScaleMode
import com.intellij.openapi.editor.Editor

class MinimapSceneBuilder(
  private val editor: Editor,
  private val model: MinimapModel,
  private val layoutCalculator: MinimapLayoutCalculator,
  private val geometryCalculator: MinimapGeometryCalculator
) {
  private var lastStructureMarkers: List<MinimapStructureMarker> = emptyList()

  fun buildSnapshot(panelWidth: Int,
                    panelHeight: Int,
                    scaleData: MinimapScaleData,
                    scaleMode: MinimapScaleMode,
                    isLegacy: Boolean): MinimapSnapshot {
    val geometry = geometryCalculator.compute(panelHeight, scaleData, scaleMode)
    val context = MinimapRenderContext(
      editor = editor,
      panelWidth = panelWidth,
      panelHeight = panelHeight,
      geometry = geometry
    )

    if (isLegacy) {
      return MinimapSnapshot(context, geometry, emptyList(), emptyList(), null, MinimapLayoutMode.EXACT)
    }

    val isCommitted = model.isDocumentCommitted()
    val structureMarkers = if (isCommitted) {
      model.getStructureMarkers().also { lastStructureMarkers = it }
    }
    else {
      lastStructureMarkers
    }

    val layoutMode = MinimapLayoutModeSelector.selectMode(context, scaleMode)
    val layout = layoutCalculator.buildLayout(context, structureMarkers, layoutMode)

    return MinimapSnapshot(context, geometry, layout.tokenEntries, layout.structureEntries, layout.metrics, layoutMode)
  }

  fun clear() {
    lastStructureMarkers = emptyList()
  }
}
