// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl

import com.intellij.openapi.editor.CustomWrap
import com.intellij.openapi.editor.CustomWrapModel
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry

class CustomWrapModelTest : AbstractEditorTest() {

  override fun setUp() {
    super.setUp()
    setUpCustomWrapSupport()
  }

  private val customWrapModel: CustomWrapModel
    get() = editor.customWrapModel

  fun testAddAndRemoveWraps() {
    initText("Hello World")
    
    val wrap = addCustomWrap(5)

    assertSize(1, customWrapModel.getWraps())
    assertSize(1, customWrapModel.getWrapsAtOffset(5))
    assertTrue(customWrapModel.hasWraps())
    assertEquals(5, wrap.offset)
    editor.customWrapModel.removeWrap(wrap)
    assertSize(0, customWrapModel.getWraps())
    assertFalse(customWrapModel.hasWraps())
  }

  fun testGetWrapsSortedByOffsetAndPriority() {
    initText("Hello World Test String For Sorting")
    
    addCustomWrap(15, 2, 2)
    addCustomWrap(15, 4, 1)
    addCustomWrap(5)
    addCustomWrap(25)
    addCustomWrap(10)
    addCustomWrap(20)
    
    val wraps = customWrapModel.getWraps()
    
    assertEquals(6, wraps.size)
    
    val offsetsAndPriorities = wraps.map { it.offset to it.priority }
    assertEquals(listOf(5 to 0, 10 to 0, 15 to 1, 15 to 2, 20 to 0, 25 to 0), offsetsAndPriorities)
  }

  fun testListenerReceivesSingleAddAndRemoveCallbacks() {
    initText("abcdef")

    val events = mutableListOf<String>()
    val disposable = Disposer.newDisposable()
    customWrapModel.addListener(object : CustomWrapModel.Listener {
      override fun customWrapAdded(wrap: CustomWrap) {
        events += "add:${wrap.offset}"
      }

      override fun customWrapRemoved(wrap: CustomWrap) {
        events += "remove:${wrap.offset}"
      }
    }, disposable)

    val wrap = addCustomWrap(3)
    customWrapModel.removeWrap(wrap)
    Disposer.dispose(disposable)
    addCustomWrap(2)
    assertEquals(listOf("add:3", "remove:3"), events)
  }

  fun testExplicitRemoveNotifiesRemovedExactlyOnce() {
    initText("abcdef")

    var removeCount = 0
    var removedOffset = -1
    customWrapModel.addListener(object : CustomWrapModel.Listener {
      override fun customWrapRemoved(wrap: CustomWrap) {
        removeCount++
        removedOffset = wrap.offset
      }
    }, getTestRootDisposable())

    val wrap = addCustomWrap(3)
    customWrapModel.removeWrap(wrap)

    assertEquals(1, removeCount)
    assertEquals(3, removedOffset)
  }

  fun testCustomWrapInvalidatedByDocumentChangeNotifiesRemovedExactlyOnce() {
    initText("abc\ndef")

    var removeCount = 0
    var removedOffset = -1
    customWrapModel.addListener(object : CustomWrapModel.Listener {
      override fun customWrapRemoved(wrap: CustomWrap) {
        removeCount++
        removedOffset = wrap.offset
      }
    }, getTestRootDisposable())

    addCustomWrap(5)

    runWriteCommand {
      editor.document.deleteString(4, 5)
    }

    assertTrue(customWrapModel.getWraps().isEmpty())
    assertEquals(1, removeCount)
    assertEquals(4, removedOffset)
  }
}
