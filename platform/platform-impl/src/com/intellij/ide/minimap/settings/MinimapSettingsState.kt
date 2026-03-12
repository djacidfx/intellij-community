// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.settings

import com.intellij.util.ui.JBUI

/**
 * @param enabled Enables Minimap for selected filetypes.
 * @param width Fixed width (scaled)
 * @param rightAligned If false, Minimap will be on the left side
 * @param fileTypes List of file extensions for which we want to show Minimap. For example txt,kt,java,zpln.
 */
data class MinimapSettingsState(var enabled: Boolean = true,
                                var width: Int = FIXED_WIDTH,
                                var scaleMode: MinimapScaleMode = MinimapScaleMode.FILL,
                                var rightAligned: Boolean = true,
                                // TODO: come up with a more user-friendly and sustainable solution
                                var fileTypes: List<String> = listOf("zpln", "py", "json", "html", "txt")) {
  companion object {
    val FIXED_WIDTH: Int = JBUI.scale(160)
  }
}
