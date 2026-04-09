// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.layout

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus

/**
 * Supplies a [MinimapLayoutProfile] for an editor.
 *
 * Register via `com.intellij.minimapLayoutProfileProvider`.
 * First non-null profile from applicable providers wins.
 */
@ApiStatus.OverrideOnly
interface MinimapLayoutProfileProvider {
  fun isApplicable(editor: Editor): Boolean = true

  /**
   * Returns profile override for [editor], or `null` to defer to the next provider.
   */
  fun getLayoutProfile(editor: Editor): MinimapLayoutProfile?

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<MinimapLayoutProfileProvider> =
      ExtensionPointName("com.intellij.minimapLayoutProfileProvider")

    fun forEditor(editor: Editor): MinimapLayoutProfile {
      for (provider in EP_NAME.extensionList) {
        if (!provider.isApplicable(editor)) continue
        val profile = provider.getLayoutProfile(editor) ?: continue
        return profile.normalized()
      }
      return MinimapLayoutProfile.DEFAULT
    }
  }
}
