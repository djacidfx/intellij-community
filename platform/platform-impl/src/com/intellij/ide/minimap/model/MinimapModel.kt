// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.model

import com.intellij.ide.minimap.MinimapStructureProvider
import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.SmartPointerManager
import java.util.IdentityHashMap

class MinimapModel(private val editor: Editor): Disposable {
  private val structureModel: StructureViewModel? = MinimapStructureProvider(editor.project, this).createModel(editor)
  private val pointerManager = editor.project?.let { SmartPointerManager.getInstance(it) }
  private var structureMarkers: List<MinimapStructureMarker> = emptyList()

  fun getStructureMarkers(): List<MinimapStructureMarker> = structureMarkers

  fun isDocumentCommitted(): Boolean {
    val project = editor.project ?: return true
    return PsiDocumentManager.getInstance(project).isCommitted(editor.document)
  }

  fun updateStructureMarkers() {
    if (!isDocumentCommitted()) return
    val root = structureModel?.root ?: return

    val previousStructureMarkers = structureMarkers
    val previousByElement = IdentityHashMap<StructureViewTreeElement, MinimapStructureMarker>(previousStructureMarkers.size)

    for (marker in previousStructureMarkers) {
      previousByElement[marker.element] = marker
    }
    val reusedStructureMarkers = IdentityHashMap<MinimapStructureMarker, Boolean>()
    val result = mutableListOf<MinimapStructureMarker>()
    val document = editor.document

    if (document.textLength == 0) {
      disposeStructureMarkers(previousStructureMarkers)
      structureMarkers = emptyList()
      return
    }

    val structureMarkerPolicy = MinimapStructureMarkerPolicy.forEditor(editor)

    MinimapStructureMarkerCollector(
      structureMarkerPolicy = structureMarkerPolicy,
      previousByElement = previousByElement,
      reusedStructureMarkers = reusedStructureMarkers,
      result = result,
      document = document,
      pointerManager = pointerManager,
    ).visit(root, includeSelf = false)

    val unused = previousStructureMarkers.filterNot { reusedStructureMarkers.containsKey(it) }
    disposeStructureMarkers(unused)
    structureMarkers = result
  }

  override fun dispose() {
    disposeStructureMarkers()
    structureMarkers = emptyList()
  }


  private fun disposeStructureMarkers(markersToDispose: List<MinimapStructureMarker> = structureMarkers) {
    val pointerManager = editor.project?.let { SmartPointerManager.getInstance(it) }
    for (marker in markersToDispose) {
      marker.rangeMarker?.dispose()
      marker.pointer?.let { pointerManager?.removePointer(it) }
    }
  }
}