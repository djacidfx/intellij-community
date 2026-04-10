// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.interaction

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus

/**
 * Controls whether minimap viewport should follow editor scrolling or use independent scrolling.
 *
 * Register via `com.intellij.minimapScrollPolicy`.
 * First applicable policy decides the mode.
 */
@ApiStatus.OverrideOnly
interface MinimapScrollPolicy {
  fun isApplicable(editor: Editor): Boolean = true

  fun useIndependentMinimapScroll(editor: Editor): Boolean = false

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<MinimapScrollPolicy> =
      ExtensionPointName("com.intellij.minimapScrollPolicy")

    fun useIndependentMinimapScroll(editor: Editor): Boolean {
      for (policy in EP_NAME.extensionList) {
        if (!policy.isApplicable(editor)) continue
        return policy.useIndependentMinimapScroll(editor)
      }
      return false
    }
  }
}
