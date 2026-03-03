// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.listeners

import com.intellij.ide.minimap.MinimapRegistry
import com.intellij.ide.minimap.caret.MinimapCaretController
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.event.VisibleAreaEvent
import com.intellij.openapi.editor.event.VisibleAreaListener
import java.awt.Rectangle

class MinimapStateListeners(
  private val parentDisposable: Disposable,
  private val editor: Editor,
  private val caretController: MinimapCaretController,
  private val scheduleStructureMarkersUpdate: () -> Unit,
  private val updateParameters: () -> Unit,
  private val repaint: () -> Unit,
) {
  private val documentListener = object : DocumentListener {
    override fun documentChanged(event: DocumentEvent) {
      if (MinimapRegistry.isLegacy()) return
      scheduleStructureMarkersUpdate()
    }
  }

  private val selectionListener = object : SelectionListener {
    override fun selectionChanged(e: SelectionEvent) {
      if (MinimapRegistry.isLegacy()) return
      repaint()
    }
  }

  private val caretListener = object : CaretListener {
    override fun caretPositionChanged(event: CaretEvent) {
      caretController.caretMoved(event.caret.offset)
    }
  }

  private val visibleAreaListener = object : VisibleAreaListener {
    private var visibleArea = Rectangle(0, 0, 0, 0)

    override fun visibleAreaChanged(e: VisibleAreaEvent) {
      val newArea = e.newRectangle
      if (visibleArea.y == newArea.y &&
          visibleArea.height == newArea.height &&
          visibleArea.width == newArea.width) {
        return
      }
      visibleArea = newArea
      updateParameters()
      repaint()
    }
  }

  fun install() {
    editor.scrollingModel.addVisibleAreaListener(visibleAreaListener, parentDisposable)
    editor.selectionModel.addSelectionListener(selectionListener, parentDisposable)
    editor.caretModel.addCaretListener(caretListener, parentDisposable)
    editor.document.addDocumentListener(documentListener, parentDisposable)
  }
}
