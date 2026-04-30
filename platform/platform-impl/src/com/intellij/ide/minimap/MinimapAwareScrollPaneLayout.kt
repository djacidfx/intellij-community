// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap

import com.intellij.ui.components.JBScrollPane
import java.awt.Component
import java.awt.Container
import javax.swing.JScrollPane

/**
 * A scroll pane layout for the minimap "insideScrollbar" mode that positions the minimap
 * between the editor viewport and the vertical scrollbar.
 *
 * The scrollbar is never moved — it stays in its natural location (inside the scroll pane,
 * or on the layered pane when sticky lines are enabled). This layout calls
 * [super.layoutContainer] to position all standard components normally, then shrinks the
 * viewport by [minimap]'s preferred width and places the minimap in the freed space.
 */
internal class MinimapScrollPaneLayout(private val minimap: Component) : JBScrollPane.Layout() {
  override fun layoutContainer(parent: Container) {
    super.layoutContainer(parent)
    val viewport = (parent as? JScrollPane)?.viewport ?: return
    val b = viewport.bounds
    val mw = minimap.preferredSize.width.coerceAtLeast(1)
    val w = (b.width - mw).coerceAtLeast(0)
    viewport.setBounds(b.x, b.y, w, b.height)
    minimap.setBounds(b.x + w, b.y, mw, b.height)
  }
}
