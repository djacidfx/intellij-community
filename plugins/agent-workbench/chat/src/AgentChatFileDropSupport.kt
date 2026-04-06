// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.chat

import com.intellij.ide.dnd.DnDDropHandler
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDSupport
import com.intellij.ide.dnd.DnDTargetChecker
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Path
import javax.swing.JComponent

internal fun installAgentChatFileDropSupport(
  editorComponent: JComponent,
  terminalTab: AgentChatTerminalTab,
  parentDisposable: Disposable,
) {
  if (ApplicationManager.getApplication() == null) {
    return
  }

  val handler = AgentChatFileDropHandler(terminalTab)
  DnDSupport.createBuilder(editorComponent)
    .setDisposableParent(parentDisposable)
    .setDropHandlerWithResult(handler)
    .setTargetChecker(handler)
    .enableAsNativeTarget()
    .disableAsSource()
    .install()
}

internal fun canHandleAgentChatFileDrop(event: DnDEvent): Boolean {
  if (!FileCopyPasteUtil.isFileListFlavorAvailable(event)) {
    return false
  }

  event.setDropPossible(true)
  return true
}

internal fun handleAgentChatFileDrop(attachedObject: Any?, terminalTab: AgentChatTerminalTab): Boolean {
  val droppedPaths = FileCopyPasteUtil.getFileListFromAttachedObject(attachedObject).map { it.toPath() }
  if (droppedPaths.isEmpty()) {
    return false
  }

  terminalTab.sendText(formatDroppedFilePaths(droppedPaths), shouldExecute = false)
  terminalTab.preferredFocusableComponent.requestFocusInWindow()
  return true
}

internal fun formatDroppedFilePaths(paths: List<Path>): String {
  return ParametersListUtil.join(paths.map(Path::toString))
}

private class AgentChatFileDropHandler(private val terminalTab: AgentChatTerminalTab) : DnDDropHandler.WithResult, DnDTargetChecker {
  override fun update(event: DnDEvent): Boolean {
    return !canHandleAgentChatFileDrop(event)
  }

  override fun tryDrop(event: DnDEvent): Boolean {
    return handleAgentChatFileDrop(event.attachedObject, terminalTab)
  }
}
