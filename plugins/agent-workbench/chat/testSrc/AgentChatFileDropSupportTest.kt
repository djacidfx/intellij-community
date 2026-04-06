// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.agent.workbench.chat

import com.intellij.ide.dnd.DnDAction
import com.intellij.ide.dnd.DnDDropHandler
import com.intellij.ide.dnd.DnDEvent
import com.intellij.ide.dnd.DnDManager
import com.intellij.ide.dnd.DnDNativeTarget
import com.intellij.ide.dnd.DnDSource
import com.intellij.ide.dnd.DnDTarget
import com.intellij.ide.dnd.DropActionHandler
import com.intellij.ide.dnd.TransferableWrapper
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.psi.PsiElement
import com.intellij.terminal.frontend.view.TerminalKeyEvent
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.replaceService
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.awt.RelativeRectangle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Cursor
import java.awt.Point
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.tree.TreeNode

@TestApplication
class AgentChatFileDropSupportTest {
  @TestDisposable
  lateinit var disposable: Disposable

  @Test
  fun installsDndTargetOnEditorRootWithoutTransferHandlers() {
    val editorComponent = JPanel()
    val terminalTab = TestAgentChatTerminalTab()

    val target = installAndGetRegisteredTarget(editorComponent, terminalTab)

    assertThat(target).isNotNull
    assertThat(editorComponent.transferHandler).isNull()
    assertThat(terminalTab.component.transferHandler).isNull()
    assertThat(terminalTab.preferredFocusableComponent.transferHandler).isNull()
  }

  @Test
  fun internalIdeFileDropPastesIntoTerminal() {
    val editorComponent = JPanel()
    val terminalTab = TestAgentChatTerminalTab()
    val droppedPaths = listOf(
      Path.of("thread-notes.md"),
      Path.of("File With Spaces.txt"),
    )
    val target = installAndGetRegisteredTarget(editorComponent, terminalTab)
    val event = FakeDnDEvent(
      attachedObject = transferableWrapper(droppedPaths),
      transferDataFlavors = arrayOf(DataFlavor.javaFileListFlavor),
    )

    assertThat(target.update(event)).isFalse()
    assertThat(event.isDropPossible).isTrue()

    val handled = (target as DnDDropHandler.WithResult).tryDrop(event)

    assertThat(handled).isTrue()
    assertThat(terminalTab.sentTexts).containsExactly(
      DroppedSentTerminalText(
        text = formatDroppedFilePaths(droppedPaths),
        shouldExecute = false,
      ),
    )
  }

  @Test
  fun nativeFileDropPastesIntoTerminal() {
    val editorComponent = JPanel()
    val terminalTab = TestAgentChatTerminalTab()
    val droppedPaths = listOf(
      Path.of("plan.txt"),
      Path.of("another file.md"),
    )
    val target = installAndGetRegisteredTarget(editorComponent, terminalTab)
    val event = FakeDnDEvent(
      attachedObject = DnDNativeTarget.EventInfo(arrayOf(DataFlavor.javaFileListFlavor), fileListTransferable(droppedPaths)),
      transferDataFlavors = arrayOf(DataFlavor.javaFileListFlavor),
    )

    assertThat(target.update(event)).isFalse()
    assertThat(event.isDropPossible).isTrue()

    val handled = (target as DnDDropHandler.WithResult).tryDrop(event)

    assertThat(handled).isTrue()
    assertThat(terminalTab.sentTexts).containsExactly(
      DroppedSentTerminalText(
        text = formatDroppedFilePaths(droppedPaths),
        shouldExecute = false,
      ),
    )
  }

  @Test
  fun nonFileDropIsIgnored() {
    val editorComponent = JPanel()
    val target = installAndGetRegisteredTarget(editorComponent, TestAgentChatTerminalTab())
    val event = FakeDnDEvent(
      attachedObject = "plain text",
      transferDataFlavors = arrayOf(DataFlavor.stringFlavor),
    )

    assertThat(target.update(event)).isTrue()
    assertThat(event.isDropPossible).isFalse()

    val handled = (target as DnDDropHandler.WithResult).tryDrop(event)

    assertThat(handled).isFalse()
  }

  private fun installAndGetRegisteredTarget(editorComponent: JComponent, terminalTab: AgentChatTerminalTab): DnDTarget {
    val dndManager = RecordingDnDManager()
    ApplicationManager.getApplication().replaceService(DnDManager::class.java, dndManager, disposable)
    installAgentChatFileDropSupport(editorComponent, terminalTab, disposable)
    assertThat(dndManager.registeredComponent).isSameAs(editorComponent)
    return checkNotNull(dndManager.registeredTarget)
  }
}

private class TestAgentChatTerminalTab : AgentChatTerminalTab {
  override val component: JComponent = JPanel()
  override val preferredFocusableComponent: JComponent = JButton("focus")
  override val coroutineScope: CoroutineScope = object : CoroutineScope {
    override val coroutineContext = Job()
  }
  override val sessionState = MutableStateFlow(TerminalViewSessionState.NotStarted)
  override val keyEventsFlow: Flow<TerminalKeyEvent> = emptyFlow()

  val sentTexts: MutableList<DroppedSentTerminalText> = mutableListOf()

  override suspend fun captureOutputCheckpoint(): AgentChatTerminalOutputCheckpoint {
    return AgentChatTerminalOutputCheckpoint(regularEndOffset = 0, alternativeEndOffset = 0)
  }

  override suspend fun awaitOutputObservation(
    checkpoint: AgentChatTerminalOutputCheckpoint,
    timeoutMs: Long,
    idleMs: Long,
  ): AgentChatTerminalOutputObservation {
    return AgentChatTerminalOutputObservation(AgentChatTerminalInputReadiness.READY, "")
  }

  override suspend fun awaitInitialMessageReadiness(
    timeoutMs: Long,
    idleMs: Long,
    checkpoint: AgentChatTerminalOutputCheckpoint?,
  ): AgentChatTerminalInputReadiness {
    return AgentChatTerminalInputReadiness.READY
  }

  override suspend fun readRecentOutputTail(): String = ""

  override fun sendText(text: String, shouldExecute: Boolean, useBracketedPasteMode: Boolean) {
    sentTexts += DroppedSentTerminalText(text, shouldExecute, useBracketedPasteMode)
  }
}

private data class DroppedSentTerminalText(
  @JvmField val text: String,
  @JvmField val shouldExecute: Boolean,
  @JvmField val useBracketedPasteMode: Boolean = true,
)

private class RecordingDnDManager : DnDManager() {
  var registeredTarget: DnDTarget? = null
  var registeredComponent: JComponent? = null

  override fun registerTarget(target: DnDTarget?, component: JComponent?) {
    registeredTarget = target
    registeredComponent = component
  }

  override fun registerTarget(target: DnDTarget, component: JComponent, parentDisposable: Disposable) {
    registeredTarget = target
    registeredComponent = component
  }

  override fun unregisterTarget(target: DnDTarget?, component: JComponent?) = Unit

  override fun registerSource(source: DnDSource, component: JComponent) = Unit

  override fun registerSource(source: DnDSource, component: JComponent, parentDisposable: Disposable) = Unit

  override fun registerSource(source: com.intellij.ide.dnd.AdvancedDnDSource) = Unit

  override fun unregisterSource(source: DnDSource, component: JComponent) = Unit

  override fun unregisterSource(source: com.intellij.ide.dnd.AdvancedDnDSource) = Unit

  override fun getLastDropHandler(): Component? = null
}

private class FakeDnDEvent(
  private val attachedObject: Any?,
  private val transferDataFlavors: Array<DataFlavor>,
) : UserDataHolderBase(), DnDEvent {
  private var dropPossible: Boolean = false
  private var action: DnDAction = DnDAction.COPY
  private var orgPoint: Point = Point()
  private var localPoint: Point = Point()
  private var cursor: Cursor = Cursor.getDefaultCursor()

  override fun getAction(): DnDAction = action

  override fun updateAction(action: DnDAction) {
    this.action = action
  }

  override fun getAttachedObject(): Any? = attachedObject

  override fun setDropPossible(possible: Boolean, aExpectedResult: String?) {
    dropPossible = possible
  }

  override fun setDropPossible(possible: Boolean) {
    dropPossible = possible
  }

  override fun setDropPossible(aExpectedResult: String, aHandler: DropActionHandler) {
    dropPossible = true
  }

  override fun getExpectedDropResult(): String? = null

  override fun getTransferDataFlavors(): Array<DataFlavor> = transferDataFlavors

  override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = transferDataFlavors.contains(flavor)

  override fun getTransferData(flavor: DataFlavor): Any {
    if (!isDataFlavorSupported(flavor)) {
      throw UnsupportedFlavorException(flavor)
    }
    return when (val value = attachedObject) {
      is Transferable -> value.getTransferData(flavor)
      is DnDNativeTarget.EventInfo -> value.transferable.getTransferData(flavor)
      is TransferableWrapper -> value.asFileList() ?: throw IOException("No files available")
      else -> throw UnsupportedFlavorException(flavor)
    }
  }

  override fun isDropPossible(): Boolean = dropPossible

  override fun getOrgPoint(): Point = orgPoint

  override fun setOrgPoint(orgPoint: Point) {
    this.orgPoint = orgPoint
  }

  override fun getPoint(): Point = localPoint

  override fun getPointOn(aComponent: Component): Point = localPoint

  override fun canHandleDrop(): Boolean = dropPossible

  override fun getHandlerComponent(): Component? = null

  override fun getCurrentOverComponent(): Component? = null

  override fun setHighlighting(aComponent: Component, aType: Int) = Unit

  override fun setHighlighting(rectangle: RelativeRectangle, aType: Int) = Unit

  override fun setHighlighting(layeredPane: JLayeredPane, rectangle: RelativeRectangle, aType: Int) = Unit

  override fun setAutoHideHighlighterInDrop(aValue: Boolean) = Unit

  override fun hideHighlighter() = Unit

  override fun setLocalPoint(localPoint: Point) {
    this.localPoint = localPoint
  }

  override fun getLocalPoint(): Point = localPoint

  override fun getRelativePoint(): RelativePoint = RelativePoint(JPanel(), localPoint)

  override fun clearDelegatedTarget() = Unit

  override fun wasDelegated(): Boolean = false

  override fun getDelegatedTarget(): DnDTarget? = null

  override fun delegateUpdateTo(target: DnDTarget): Boolean = false

  override fun delegateDropTo(target: DnDTarget) = Unit

  override fun getCursor(): Cursor = cursor

  override fun setCursor(cursor: Cursor) {
    this.cursor = cursor
  }

  override fun cleanUp() = Unit
}

private fun fileListTransferable(paths: List<Path>): Transferable {
  return object : Transferable {
    private val files = paths.map(Path::toFile)

    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor

    override fun getTransferData(flavor: DataFlavor): Any = files
  }
}

private fun transferableWrapper(paths: List<Path>): TransferableWrapper {
  return object : TransferableWrapper {
    private val files = paths.map(Path::toFile)

    override fun asFileList(): List<File> = files

    override fun getTreeNodes(): Array<TreeNode>? = null

    override fun getPsiElements(): Array<PsiElement>? = null
  }
}
