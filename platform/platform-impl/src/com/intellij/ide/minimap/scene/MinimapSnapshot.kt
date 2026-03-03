// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.scene

import com.intellij.ide.minimap.geometry.MinimapGeometryData
import com.intellij.ide.minimap.render.MinimapRenderEntry
import com.intellij.ide.minimap.render.MinimapRenderContext

data class MinimapSnapshot(
  val context: MinimapRenderContext,
  val geometry: MinimapGeometryData,
  val entries: List<MinimapRenderEntry>
)