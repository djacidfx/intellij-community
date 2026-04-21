// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.registry.Registry
import org.jetbrains.annotations.ApiStatus
import java.util.EventListener

/**
 * Model for managing custom (user-defined) soft wraps in an editor.
 * Custom wraps persist across automatic soft wrap recalculations.
 *
 * @see Editor.getCustomWrapModel
 */
@ApiStatus.Experimental
@ApiStatus.NonExtendable
interface CustomWrapModel {
  /**
   * Adds a custom wrap at the specified offset.
   *
   * Custom wraps may not be adjacent to actual line breaks in the document.
   * Adding wrap at such offsets returns `null`.
   * If the wrap lands at such offset after document modifications,
   * it will be removed automatically (see [Listener.customWrapRemoved]).
   *
   * Note: custom wraps are meant to break existing lines;
   * to insert empty lines use [InlayModel.addBlockElement].
   *
   * @param priority Only one custom wrap is rendered at a single offset. The lowest priority wins.
   * @param indentInColumns Non-negative number of columns to indent after the wrap.
   */
  fun addWrap(offset: Int, indentInColumns: Int, priority: Int = 0): CustomWrap?
  fun getWraps(): List<CustomWrap>
  fun getWrapsInRange(startOffset: Int, endOffset: Int): List<CustomWrap>
  fun getWrapsAtOffset(offset: Int): List<CustomWrap>
  fun hasWraps(): Boolean
  fun removeWrap(wrap: CustomWrap)

  fun addListener(listener: Listener, disposable: Disposable)

  interface Listener : EventListener {
    fun customWrapAdded(wrap: CustomWrap) {}
    fun customWrapRemoved(wrap: CustomWrap) {}
  }

  companion object {
    @JvmStatic
    fun isCustomWrapsSupportEnabled(): Boolean =
      Registry.`is`("editor.custom.soft.wraps.support.enabled") &&
      Registry.`is`("editor.use.new.soft.wraps.impl")
  }
}

