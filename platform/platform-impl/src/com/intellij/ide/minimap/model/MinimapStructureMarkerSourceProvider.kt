// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.model

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus

/**
 * Allows IDE/plugins to resolve structure elements to minimap marker sources.
 *
 * This hook is useful for structure trees whose values are not plain `PsiElement`/`TextRange`
 * (for example, notebook cell nodes backed by interval models).
 *
 * Register via `com.intellij.minimapStructureMarkerSourceProvider`.
 * First non-null source from applicable providers wins.
 */
@ApiStatus.OverrideOnly
interface MinimapStructureMarkerSourceProvider {
  fun isApplicable(editor: Editor): Boolean = true

  /**
   * Returns resolved marker source for [element], or `null` to defer to the next provider.
   */
  fun resolveSource(editor: Editor, element: StructureViewTreeElement, value: Any): MinimapStructureMarkerSource?

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<MinimapStructureMarkerSourceProvider> =
      ExtensionPointName("com.intellij.minimapStructureMarkerSourceProvider")

    fun resolve(editor: Editor, element: StructureViewTreeElement, value: Any): MinimapStructureMarkerSource? {
      for (provider in EP_NAME.extensionList) {
        if (!provider.isApplicable(editor)) continue
        val source = provider.resolveSource(editor, element, value)
        if (source != null) return source
      }
      return null
    }
  }
}
