// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.hover

import com.intellij.ide.minimap.layout.MinimapLayoutMode
import com.intellij.ide.minimap.scene.MinimapSnapshot
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus

/**
 * Controls whether default minimap hover interaction is enabled for an editor.
 *
 * Register via `com.intellij.minimapHoverPolicy`.
 * The first applicable policy wins.
 */
@ApiStatus.OverrideOnly
interface MinimapHoverPolicy {
  fun isApplicable(editor: Editor): Boolean = true

  fun isHoverEnabled(editor: Editor, snapshot: MinimapSnapshot): Boolean

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<MinimapHoverPolicy> =
      ExtensionPointName("com.intellij.minimapHoverPolicy")

    fun forEditor(editor: Editor): MinimapHoverPolicy {
      return EP_NAME.extensionList.firstOrNull { it.isApplicable(editor) } ?: DefaultMinimapHoverPolicy
    }
  }
}

private object DefaultMinimapHoverPolicy : MinimapHoverPolicy {
  override fun isHoverEnabled(editor: Editor, snapshot: MinimapSnapshot): Boolean {
    return snapshot.layoutMode == MinimapLayoutMode.EXACT
  }
}
