// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.paint

import com.intellij.ide.minimap.render.MinimapRenderEntry
import com.intellij.ide.minimap.render.MinimapRenderContext
import com.intellij.openapi.editor.Editor
import java.awt.Graphics2D

@Suppress("UNUSED_PARAMETER")
class MinimapSelectionPainter(private val editor: Editor) {
  fun paint(graphics: Graphics2D, context: MinimapRenderContext, entries: List<MinimapRenderEntry>) {
    // todo: implement in case we like to indicate "selections"
  }
}
