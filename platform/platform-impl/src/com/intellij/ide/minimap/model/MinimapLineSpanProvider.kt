// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.model

import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus
import java.util.LinkedHashMap

/**
 * Contributes per-line projected span overrides for minimap layout.
 *
 * A span of:
 * - `1` keeps the default one-slot mapping;
 * - `N > 1` expands a logical line into multiple projected slots;
 * - `0` is reserved for internal compression (for example hidden lines in collapsed fold regions).
 *
 * Register via `com.intellij.minimapLineSpanProvider`.
 * Applicable providers are merged in registration order; last provider wins for the same line.
 */
@ApiStatus.OverrideOnly
interface MinimapLineSpanProvider {
  fun isApplicable(editor: Editor): Boolean = true

  /**
   * Returns logical-line -> span overrides.
   * Invalid line numbers are ignored. Values are normalized to at least `1`.
   */
  fun getLineSpanOverrides(editor: Editor, document: Document, logicalLineCount: Int): Map<Int, Int>

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<MinimapLineSpanProvider> =
      ExtensionPointName("com.intellij.minimapLineSpanProvider")

    fun collect(editor: Editor, document: Document, logicalLineCount: Int): Map<Int, Int> {
      if (logicalLineCount <= 0) return emptyMap()

      var merged: MutableMap<Int, Int>? = null
      for (provider in EP_NAME.extensionList) {
        if (!provider.isApplicable(editor)) continue
        val overrides = provider.getLineSpanOverrides(editor, document, logicalLineCount)
        if (overrides.isEmpty()) continue

        val target = merged ?: LinkedHashMap<Int, Int>().also { merged = it }
        for ((logicalLine, spanRaw) in overrides) {
          if (logicalLine !in 0 until logicalLineCount) continue
          target[logicalLine] = spanRaw.coerceAtLeast(1)
        }
      }
      return merged ?: emptyMap()
    }
  }
}
