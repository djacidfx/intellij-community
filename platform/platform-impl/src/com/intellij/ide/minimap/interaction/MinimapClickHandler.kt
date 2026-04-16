// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.interaction

import com.intellij.ide.minimap.MinimapPanel
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus
import java.awt.event.MouseEvent

/**
 * Allows plugins to override minimap mouse interaction behavior.
 *
 * Register via `com.intellij.minimapClickHandler`.
 * Applicable handlers are evaluated in registration order; the first handler that returns `true` wins.
 */
@ApiStatus.OverrideOnly
interface MinimapClickHandler {
  fun isApplicable(editor: Editor): Boolean = true

  fun handleClick(panel: MinimapPanel, event: MouseEvent): Boolean = false

  fun handleMouseMoved(panel: MinimapPanel, event: MouseEvent): Boolean = false

  fun handleMouseExited(panel: MinimapPanel, event: MouseEvent): Boolean = false

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<MinimapClickHandler> =
      ExtensionPointName("com.intellij.minimapClickHandler")

    fun handleClick(panel: MinimapPanel, event: MouseEvent): Boolean {
      val editor = panel.editor
      for (handler in EP_NAME.extensionList) {
        if (!handler.isApplicable(editor)) continue
        if (handler.handleClick(panel, event)) return true
      }
      return false
    }

    fun handleMouseMoved(panel: MinimapPanel, event: MouseEvent): Boolean {
      val editor = panel.editor
      for (handler in EP_NAME.extensionList) {
        if (!handler.isApplicable(editor)) continue
        if (handler.handleMouseMoved(panel, event)) return true
      }
      return false
    }

    fun handleMouseExited(panel: MinimapPanel, event: MouseEvent): Boolean {
      val editor = panel.editor
      for (handler in EP_NAME.extensionList) {
        if (!handler.isApplicable(editor)) continue
        if (handler.handleMouseExited(panel, event)) return true
      }
      return false
    }
  }
}
