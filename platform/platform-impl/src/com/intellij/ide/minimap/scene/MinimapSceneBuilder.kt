// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.scene

import com.intellij.ide.minimap.geometry.MinimapGeometryCalculator
import com.intellij.ide.minimap.layout.MinimapLayoutCalculator
import com.intellij.ide.minimap.model.MinimapStructureMarker
import com.intellij.ide.minimap.model.MinimapModel
import com.intellij.ide.minimap.render.MinimapRenderContext
import com.intellij.openapi.editor.Editor

class MinimapSceneBuilder(
  private val editor: Editor,
  private val model: MinimapModel,
  private val layoutCalculator: MinimapLayoutCalculator,
  private val geometryCalculator: MinimapGeometryCalculator
) {
  private var lastStructureMarkers: List<MinimapStructureMarker> = emptyList()

  fun buildSnapshot(panelWidth: Int, panelHeight: Int, stateWidth: Int, isLegacy: Boolean): MinimapSnapshot {
    val geometry = geometryCalculator.compute(panelHeight, stateWidth)
    val context = MinimapRenderContext(
      editor = editor,
      panelWidth = panelWidth,
      panelHeight = panelHeight,
      geometry = geometry
    )

    if (isLegacy) {
      return MinimapSnapshot(context, geometry, emptyList())
    }

    val isCommitted = model.isDocumentCommitted()
    val structureMarkers = if (isCommitted) {
      model.getStructureMarkers().also { lastStructureMarkers = it }
    }
    else {
      lastStructureMarkers
    }

    val entries = if (structureMarkers.isEmpty()) emptyList() else layoutCalculator.buildLayout(context, structureMarkers)
    return MinimapSnapshot(context, geometry, entries)
  }

  fun clear() {
    lastStructureMarkers = emptyList()
  }
}