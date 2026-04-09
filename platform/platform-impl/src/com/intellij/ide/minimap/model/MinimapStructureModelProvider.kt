// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.model

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus

/**
 * Supplies a structure model used by minimap for an editor.
 *
 * This hook allows IDE/plugins to provide a custom structure model for minimap
 * when default language-based structure view lookup is insufficient.
 *
 * Register via `com.intellij.minimapStructureModelProvider`.
 * First non-null model from applicable providers wins.
 */
@ApiStatus.OverrideOnly
interface MinimapStructureModelProvider {
  fun isApplicable(editor: Editor): Boolean = true

  /**
   * Returns a structure model for [editor], or `null` to defer to the next provider.
   *
   * Providers should ensure returned model is disposed with [parentDisposable].
   */
  fun createStructureModel(editor: Editor, parentDisposable: Disposable): StructureViewModel?

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<MinimapStructureModelProvider> =
      ExtensionPointName("com.intellij.minimapStructureModelProvider")

    fun resolve(editor: Editor, parentDisposable: Disposable): StructureViewModel? {
      for (provider in EP_NAME.extensionList) {
        if (!provider.isApplicable(editor)) continue
        val model = provider.createStructureModel(editor, parentDisposable)
        if (model != null) return model
      }
      return null
    }
  }
}
