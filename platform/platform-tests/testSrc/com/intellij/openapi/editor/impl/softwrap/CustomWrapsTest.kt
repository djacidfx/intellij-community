// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.softwrap

import com.intellij.formatting.Indent
import com.intellij.openapi.editor.SoftWrap
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.AbstractEditorTest
import com.intellij.openapi.editor.impl.EditorImpl
import org.junit.jupiter.api.assertAll

abstract class CustomWrapsTestBase : AbstractEditorTest() {
  protected open val useSoftWraps: Boolean = false

  override fun setUp() {
    super.setUp()
    setUpCustomWrapSupport()
  }

  fun initTextAndSoftWraps(fileText: String, charCountToSoftWrapAt: Int = 5) {
    super.initText(fileText)
    if (useSoftWraps) {
      configureSoftWraps(charCountToSoftWrapAt)
    }
  }

  fun testCustomWrapIsRegisteredInStorage() {
    initTextAndSoftWraps("0123456789")
    val wrapOffset = 5
    assertNotNull(editor.customWrapModel.addWrap(wrapOffset, 0, 0))
    assertNotNull(editor.softWrapModel.getSoftWrap(wrapOffset))
    (editor as EditorImpl).validateState()
  }

  fun testDeleteInvalidatesWrapsInsideRange() {
    initTextAndSoftWraps("0123456789")
    editor.customWrapModel.addWrap(1, 0, 0)
    editor.customWrapModel.addWrap(4, 0, 0)
    editor.customWrapModel.addWrap(8, 0, 0)

    runWriteCommand {
      editor.document.deleteString(2, 6)
    }

    assertEquals(listOf(1, 4), registeredSoftWraps().map { it.start })
    assertStorageConsistent()
  }

  fun testBoundaryMergeKeepsSmallerPriorityWrap() {
    initTextAndSoftWraps("0123456789")
    editor.customWrapModel.addWrap(2, 5, 10)
    editor.customWrapModel.addWrap(4, 1, 1)

    runWriteCommand {
      editor.document.deleteString(2, 4)
    }

    val wrapsAtOffset = registeredSoftWraps().filter { it.start == 2 }
    assertEquals(1, wrapsAtOffset.size)
    assertEquals(1, wrapsAtOffset.single().indentInColumns)
    assertStorageConsistent()
  }

  fun testDeleteAndMerge() {
    initTextAndSoftWraps("0123456789")
    editor.customWrapModel.addWrap(2, 0, 2)
    editor.customWrapModel.addWrap(4, 0, 0)
    editor.customWrapModel.addWrap(6, 4, 0)

    runWriteCommand {
      editor.document.deleteString(2, 6)
    }

    val wraps = registeredSoftWraps()
    val customWraps = wraps.filter { it.isCustomSoftWrap }
    assertEquals(listOf(2), customWraps.map { it.start })
    assertEquals(4, customWraps.single().indentInColumns)
    if (useSoftWraps) {
      assertEquals(listOf(2, 3), wraps.map { it.start })
    }
    else {
      assertEquals(listOf(2), wraps.map { it.start })
    }
    assertStorageConsistent()
  }

  fun testCollapsedFoldFiltersWrapsInside() {
    initTextAndSoftWraps("0123456789")
    addCollapsedFoldRegion(2, 7, "...")
    editor.customWrapModel.addWrap(2, 0, 0)
    editor.customWrapModel.addWrap(5, 0, 0)

    val wraps = registeredSoftWraps()
    assertSize(1, wraps)
    assertEquals(2, wraps.single().start)
    assertStorageConsistent()
  }

  fun testMoveWithWrapsInCorridorKeepsStorageSorted() {
    initTextAndSoftWraps("0123456789ABCDEFGHIJ")
    editor.customWrapModel.addWrap(6, 0, 5)
    editor.customWrapModel.addWrap(8, 0, 0)
    editor.customWrapModel.addWrap(13, 0, 1)

    runWriteCommand {
      (editor.document as DocumentEx).moveText(5, 9, 12)
    }

    assertStorageConsistent()
    (editor as EditorImpl).validateState()
  }

  fun testMixedEditsPreserveEditorState() {
    initTextAndSoftWraps("0123456789ABCDEFGHIJ")
    editor.customWrapModel.addWrap(2, 0, 3)
    editor.customWrapModel.addWrap(6, 0, 1)
    editor.customWrapModel.addWrap(15, 0, 2)

    runWriteCommand {
      editor.document.insertString(4, "zz")
      editor.document.deleteString(8, 10)
      (editor.document as DocumentEx).moveText(3, 7, 14)
    }

    assertStorageConsistent()
    (editor as EditorImpl).validateState()
  }

  protected fun registeredSoftWraps(): List<SoftWrap> {
    return (editor as EditorEx).softWrapModel.registeredSoftWraps
  }

  protected fun assertStorageConsistent() {
    val wraps = registeredSoftWraps()
    var previous = -1
    for (wrap in wraps) {
      val current = wrap.start
      assertTrue(current > previous)
      val fold = editor.foldingModel.getCollapsedRegionAtOffset(current)
      assertTrue(fold == null || fold.startOffset == current)
      previous = current
    }
  }

  protected fun addCustomWrap(offset: Int, indent: Int = 0) {
    addCustomWrap(offset, indent, 0)
  }
}

class CustomWrapsOnlyTest : CustomWrapsTestBase()

class CustomWrapsWithSoftWrapsEnabledTest : CustomWrapsTestBase() {
  override val useSoftWraps: Boolean = true

  fun testCorrectIndentsForSoftWrapsInALongLineMixedWithCustomWraps() {
    initTextAndSoftWraps("    012340123401234012340123401234567890123456789", 10)
    addCustomWrap(9, indent = 0)
    addCustomWrap(14, indent = 0)
    addCustomWrap(19, indent = 0)
    addCustomWrap(24, indent = 0)
    addCustomWrap(29, indent = 2)

    fun doCheck() {
      val wraps = registeredSoftWraps()
      assertSize(8, wraps)
      assertTrue(wraps.slice(0..<5).all { it.isCustomSoftWrap })
      assertEquals(wraps.slice(5..<8).map { it.indentInColumns }, listOf(5, 5, 5))
      assertEquals(wraps.slice(5..<8).map { it.start }, listOf(37, 42, 47))
    }

    doCheck()
    runWriteCommand { editor.document.insertString(39, "0") }
    doCheck()
  }
}
