// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap

import com.intellij.ide.minimap.settings.MinimapSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.CoroutineScope
import java.awt.BorderLayout
import javax.swing.JPanel

@Service(Service.Level.APP)
class MinimapService(private val scope: CoroutineScope) : Disposable {
  private val settings = MinimapSettings.getInstance()

  private val onSettingsChange = { type: MinimapSettings.SettingsChangeType ->
    if (type == MinimapSettings.SettingsChangeType.WithUiRebuild) {
      updateAllEditors()
    }
  }

  init {
    MinimapSettings.getInstance().settingsChangeCallback += onSettingsChange
  }

  override fun dispose() {
    MinimapSettings.getInstance().settingsChangeCallback -= onSettingsChange
  }

  fun editorOpened(editor: Editor) {
    if (!settings.state.enabled) return
    getEditorImpl(editor)?.let { addMinimap(it) }
  }


  fun updateAllEditors() {
    EditorFactory.getInstance().allEditors.forEach { editor ->
      getEditorImpl(editor)?.let {
        removeMinimap(it)
        if (settings.state.enabled) {
          addMinimap(it)
        }
      }
    }
  }

  private fun getEditorImpl(editor: Editor): EditorImpl? {
    val editorImpl = editor as? EditorImpl ?: return null
    if (editorImpl.editorKind != EditorKind.MAIN_EDITOR) return null
    val project = editorImpl.project ?: return null
    val document = editorImpl.document
    val virtualFile = PsiDocumentManager.getInstance(project).getPsiFile(document)?.virtualFile
                      ?: FileDocumentManager.getInstance().getFile(document)
                      ?: return null

    val enabledFileTypes = settings.state.fileTypes.toSet()
    if (enabledFileTypes.isEmpty()) return null
    if (!isFileTypeEnabled(virtualFile, enabledFileTypes)) return null
    return editorImpl
  }

  private fun isFileTypeEnabled(virtualFile: VirtualFile, enabled: Set<String>): Boolean {
    val ext = virtualFile.extension?.lowercase()
    if (ext != null && ext in enabled) return true

    // separate processing for plain text
    if ("txt" !in enabled) return false
    if (ext == "log") return true

    return virtualFile.fileType == PlainTextFileType.INSTANCE
  }

  private fun getPanel(fileEditor: EditorImpl): JPanel? {
    return fileEditor.component as? JPanel
  }

  private fun addMinimap(textEditor: EditorImpl) {
    val panel = getPanel(textEditor) ?: return

    val where = if (settings.state.rightAligned) BorderLayout.LINE_END else BorderLayout.LINE_START

    val borderLayout = panel.layout as? BorderLayout ?: return
    if (borderLayout.getLayoutComponent(where) != null) return

    val disposable = textEditor.disposable
    val minimapPanel = MinimapPanel(disposable, scope, textEditor, panel)

    panel.add(minimapPanel, where)
    textEditor.putUserData(MINI_MAP_PANEL_KEY, minimapPanel)

    Disposer.register(textEditor.disposable) {
      textEditor.getUserData(MINI_MAP_PANEL_KEY)?.onClose()
      textEditor.putUserData(MINI_MAP_PANEL_KEY, null)
    }

    panel.revalidate()
    panel.repaint()
  }

  private fun removeMinimap(editor: EditorImpl) {
    val minimapPanel = editor.getUserData(MINI_MAP_PANEL_KEY) ?: return
    minimapPanel.onClose()
    editor.putUserData(MINI_MAP_PANEL_KEY, null)

    minimapPanel.parent?.apply {
      remove(minimapPanel)
      revalidate()
      repaint()
    }
  }

  companion object {
    fun getInstance(): MinimapService = service<MinimapService>()
    private val MINI_MAP_PANEL_KEY: Key<MinimapPanel> = Key.create("com.intellij.ide.minimap.panel")
  }
}
