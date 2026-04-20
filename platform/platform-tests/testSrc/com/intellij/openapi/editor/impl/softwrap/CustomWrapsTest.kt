// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.editor.impl.softwrap

import com.intellij.openapi.editor.SoftWrap
import com.intellij.openapi.editor.VisualPosition
import com.intellij.openapi.editor.ex.DocumentEx
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.AbstractEditorTest
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.editor.impl.SoftWrapModelImpl
import java.awt.Graphics
import java.awt.Point

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

  protected fun installSoftWrapPainterWithWidth(width: Int) {
    (editor.softWrapModel as SoftWrapModelImpl).setSoftWrapPainter(object : SoftWrapPainter {
      override fun paint(g: Graphics, drawingType: SoftWrapDrawingType, x: Int, y: Int, lineHeight: Int): Int = width

      override fun getDrawingHorizontalOffset(g: Graphics, drawingType: SoftWrapDrawingType, x: Int, y: Int, lineHeight: Int): Int = width

      override fun getMinDrawingWidth(drawingType: SoftWrapDrawingType): Int = width

      override fun canUse(): Boolean = true

      override fun reinit() {}
    })
  }

  fun testCustomWrapIgnoresSoftWrapMarkerWidthInCoordinateMapping() {
    initTextAndSoftWraps("ab")
    val spaceWidth = editor.visualPositionToXY(VisualPosition(0, 1)).x - editor.visualPositionToXY(VisualPosition(0, 0)).x
    installSoftWrapPainterWithWidth(spaceWidth * 3)
    addCustomWrap(1, indent = 2)

    val firstLineY = editor.visualPositionToXY(VisualPosition(0, 0)).y
    val wrappedLineY = editor.visualPositionToXY(VisualPosition(1, 0)).y
    assertEquals(VisualPosition(1, 1), editor.xyToVisualPosition(Point(spaceWidth / 2, wrappedLineY)))

    val endOfWrappedLineX = editor.visualPositionToXY(VisualPosition(0, 1)).x
    val nextVirtualColumnX = editor.visualPositionToXY(VisualPosition(0, 2)).x
    assertEquals(spaceWidth, nextVirtualColumnX - endOfWrappedLineX)
    assertEquals(VisualPosition(0, 2), editor.xyToVisualPosition(Point(endOfWrappedLineX + spaceWidth / 2, firstLineY)))
  }

  fun testCustomWrapIgnoresSoftWrapMarkerWidthBeforeAfterLineEndInlayCoordinateMapping() {
    initTextAndSoftWraps("ab")
    val spaceWidth = editor.visualPositionToXY(VisualPosition(0, 1)).x - editor.visualPositionToXY(VisualPosition(0, 0)).x
    val inlayWidth = spaceWidth * 2
    installSoftWrapPainterWithWidth(spaceWidth * 3)
    addCustomWrap(1)
    addAfterLineEndInlay(2, inlayWidth)

    val wrappedLineY = editor.visualPositionToXY(VisualPosition(1, 0)).y
    val wrappedTextEndX = editor.visualPositionToXY(VisualPosition(1, 1)).x
    val firstInlayX = editor.visualPositionToXY(VisualPosition(1, 2)).x
    assertEquals(spaceWidth, firstInlayX - wrappedTextEndX)
    assertEquals(VisualPosition(1, 2), editor.xyToVisualPosition(Point(firstInlayX + inlayWidth / 4, wrappedLineY)))
    assertEquals(VisualPosition(1, 3), editor.xyToVisualPosition(Point(firstInlayX + inlayWidth * 3 / 4, wrappedLineY)))
  }

  fun testCustomWrapIgnoresSoftWrapMarkerWidthInPreferredSize() {
    initTextAndSoftWraps("ab")
    val widthWithoutWrap = editor.contentComponent.preferredSize.width
    installSoftWrapPainterWithWidth(editor.visualPositionToXY(VisualPosition(0, 1)).x * 3)
    addCustomWrap(1)

    val widthWithWrap = editor.contentComponent.preferredSize.width
    assertTrue(widthWithWrap < widthWithoutWrap)
  }
}


class CustomWrapsOnlyTest : CustomWrapsTestBase()

class CustomWrapsWithSoftWrapsEnabledTest : CustomWrapsTestBase() {
  override val useSoftWraps: Boolean = true

  fun testRegularSoftWrapStillUsesMarkerWidthWhenCustomWrapsArePresent() {
    initTextAndSoftWraps("abcdefghijklmnop", 3)
    val spaceWidth = editor.visualPositionToXY(VisualPosition(0, 1)).x - editor.visualPositionToXY(VisualPosition(0, 0)).x
    val markerWidth = spaceWidth * 3
    installSoftWrapPainterWithWidth(markerWidth)
    addCustomWrap(1)

    val regularSoftWrap = registeredSoftWraps().firstOrNull { !it.isCustomSoftWrap }
    assertNotNull(regularSoftWrap)

    val beforeWrapPosition = editor.offsetToVisualPosition(regularSoftWrap!!.start, false, true)
    val beforeWrapX = editor.visualPositionToXY(beforeWrapPosition).x
    val afterMarkerX = editor.visualPositionToXY(VisualPosition(beforeWrapPosition.line, beforeWrapPosition.column + 1)).x
    assertEquals(markerWidth, afterMarkerX - beforeWrapX)
  }

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
