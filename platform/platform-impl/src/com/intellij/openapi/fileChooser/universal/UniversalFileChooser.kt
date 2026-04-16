// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileChooser.universal

import com.intellij.icons.AllIcons
import com.intellij.ide.IdeBundle
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDialog
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSystemTree
import com.intellij.openapi.fileChooser.FileTextField
import com.intellij.openapi.fileChooser.PathChooserDialog
import com.intellij.openapi.fileChooser.ex.FileSystemTreeImpl
import com.intellij.openapi.fileChooser.tree.FileTreeModel
import com.intellij.openapi.observable.util.whenDisposed
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.getUserData
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.putUserData
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.ManagingFS
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.openapi.vfs.newvfs.RefreshQueue
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.asNioPath
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.UIBundle
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.breadcrumbs.Breadcrumbs
import com.intellij.ui.components.breadcrumbs.Crumb
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.AlignY
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.tree.AsyncTreeModel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.Consumer
import com.intellij.util.containers.toArray
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.Predicate
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.tree.TreeSelectionModel

@ApiStatus.Internal
object UniversalFileChooser {
  @JvmStatic
  fun canUseIn(project: Project?): Boolean {
    return Registry.`is`("universal.file.chooser.is.enabled")
  }

  @JvmStatic
  fun create(project: Project?, descriptor: FileChooserDescriptor): Dialog {
    val currProject = project ?: ProjectManager.getInstance().defaultProject
    return Dialog(currProject, descriptor)
  }

  /**
   * Capable of choosing files in a local file system and in Docker/WSL containers.
   */
  class Dialog(
    val project: Project,
    private val descriptor: FileChooserDescriptor,
  ) : DialogWrapper(project), FileChooserDialog, PathChooserDialog {
    private lateinit var mainPanel: Panel

    init {
      init()
      title = descriptor.title ?: UIBundle.message("file.chooser.default.title")
    }

    override fun getDimensionServiceKey(): String = "UniversalFileChooserDialog"

    override fun choose(project: Project?, vararg toSelect: VirtualFile?): Array<out VirtualFile?> {
      if (!toSelect.isEmpty()) {
        toSelect.first()?.let{ mainPanel.preselectFile(it) }
      }
      if (this.showAndGet()) {
        return mainPanel.getSelectedFiles().toArray(VirtualFile.EMPTY_ARRAY)
      }
      return emptyArray()
    }

    override fun choose(toSelect: VirtualFile?, callback: Consumer<in MutableList<VirtualFile>>) {
      mainPanel.preselectFile(toSelect)
      if (showAndGet()) {
        val mutableList = mutableListOf<VirtualFile>()
        mutableList.addAll(mainPanel.getSelectedFiles())
        callback.consume(mutableList)
      }
    }

    override fun createCenterPanel(): JComponent {
      mainPanel = Panel(this.disposable, descriptor, project, ::doOKAction)
      return mainPanel
    }
  }

  class Panel(
    disposable: Disposable,
    descriptor: FileChooserDescriptor,
    private val project: Project,
    okAction: Runnable,
  ) : JPanel() {

    companion object {
      private val FILE_VIEW_KEY: Key<FileView?> = Key.create<FileView>("universalFileChooser.fileView")
      private const val LOCATIONS_PROPORTION_KEY = "universalFileChooser.locationsProportion"
      private const val LOCATIONS_DEFAULT_PROPORTION = 0.2f
    }

    private val tabbedPane: JBTabbedPane
    private val fileViews: MutableList<FileView> = mutableListOf()
    @Suppress("OPT_IN_USAGE")
    private val scope = GlobalScope.childScope("UniversalFileChooser")

    init {
      layout = BorderLayout()
      val screenSize = Toolkit.getDefaultToolkit().screenSize
      preferredSize = Dimension(screenSize.width / 2, screenSize.height / 2)
      tabbedPane = JBTabbedPane()
      for (contributor in UniversalFileChooserContributor.EP_NAME.extensionList) {
        val fileView = FileView(contributor, descriptor, disposable, project, okAction, scope)
        fileViews.add(fileView)
        tabbedPane.addTab(contributor.tabTitle, fileView.topComponent)
      }

      preselectProjectTab(project)

      val splitter = OnePixelSplitter(false, LOCATIONS_PROPORTION_KEY, LOCATIONS_DEFAULT_PROPORTION)
      splitter.firstComponent = createLocationsPanel(project)
      splitter.secondComponent = tabbedPane
      add(splitter, BorderLayout.CENTER)

      disposable.whenDisposed {
        scope.cancel()
      }
    }

    private fun preselectProjectTab(project: Project) {
      val projectContributor = if (project.isDefault) null else {
        project.projectFilePath?.let { projectPath ->
          UniversalFileChooserContributor.findOwner(Path.of(projectPath))
        }
      }
      projectContributor?.let { contributor ->
        tabbedPane.indexOfTab(contributor.tabTitle)
          .takeIf { it >= 0 }?.let { tabbedPane.selectedIndex = it }
      }
    }

    fun preselectFile(toSelect: VirtualFile?) {
      if (toSelect == null) return
      val nioPath = runCatching { toSelect.toNioPath() }.getOrNull() ?: return
      val index = fileViews.indexOfFirst { it.contributor.ownsPath(nioPath) }
      if (index < 0) return
      tabbedPane.selectedIndex = index
      fileViews[index].fileToSelect = toSelect
    }

    fun getSelectedFiles(): List<VirtualFile> {
      val fileView = (tabbedPane.selectedComponent as JComponent).getUserData(FILE_VIEW_KEY)
      return fileView?.getSelectedFiles() ?: emptyList()
    }

    private fun navigateToFile(file: VirtualFile) {
      val nioPath = runCatching { file.toNioPath() }.getOrNull() ?: return
      val index = fileViews.indexOfFirst { it.contributor.ownsPath(nioPath) }
      if (index < 0) return
      tabbedPane.selectedIndex = index
      val targetView = fileViews[index]
      targetView.fileToSelect = file
      targetView.fileTree.select(file) { targetView.fileTree.expand(file, null) }
    }

    private fun getActiveFileView(): FileView? {
      val component = tabbedPane.selectedComponent as? JComponent ?: return null
      return component.getUserData(FILE_VIEW_KEY)
    }

    private data class LocationData(
      val icon: Icon,
      val text: @Nls String,
      val action: Runnable,
    )

    private fun createLocationsPanel(project: Project): JComponent {
      val locations = buildList {
        add(LocationData(
          icon = AllIcons.Nodes.HomeFolder,
          text = IdeBundle.message("universal.file.chooser.action.home.text"),
          action = { getActiveFileView()?.navigateToHome() }
        ))
        if (!project.isDefault) {
          add(LocationData(
            icon = AllIcons.Nodes.Project,
            text = IdeBundle.message("universal.file.chooser.location.project"),
            action = { navigateToProject() }
          ))
        }
      }
      val locationList = JBList(locations)
      locationList.selectionMode = ListSelectionModel.SINGLE_SELECTION
      locationList.cellRenderer = object : ColoredListCellRenderer<LocationData>() {
        override fun customizeCellRenderer(list: JList<out LocationData>, value: LocationData, index: Int, selected: Boolean, hasFocus: Boolean) {
          icon = value.icon
          append(value.text)
        }
      }
      locationList.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
          val index = locationList.locationToIndex(e.point)
          if (index >= 0) {
            locationList.model.getElementAt(index).action.run()
            locationList.clearSelection()
          }
        }
      })
      return panel {
        row {
          cell(locationList).align(AlignX.FILL)
        }
      }
    }

    private fun navigateToProject() {
      val basePath = project.basePath ?: return
      scope.launch {
        withContext(Dispatchers.IO) {
          val vFile = VfsUtil.findFile(Path.of(basePath), true)
          runOnEdt {
            if (vFile != null) navigateToFile(vFile)
          }
        }
      }
    }


    class FileView(
      val contributor: UniversalFileChooserContributor,
      private val descriptor: FileChooserDescriptor,
      disposable: Disposable,
      project: Project,
      okAction: Runnable,
      val scope: CoroutineScope
    ) {
      val topComponent: JComponent
      val fileTree: FileSystemTreeImpl
      private val roots: MutableList<Path> = mutableListOf()
      var fileToSelect: VirtualFile? = null
      private val breadcrumbs = Breadcrumbs()
      private var currentCrumbs: List<FileCrumb> = emptyList()
      private val barCardLayout = CardLayout()
      private val barPanel = JPanel(barCardLayout)
      private val pathTextField: FileTextField = FileChooserFactory.getInstance().createFileTextField(descriptor, disposable)

      companion object {
        private const val LOADING_CARD = "loading"
        private const val TREE_CARD = "tree"
        private const val BREADCRUMBS_CARD = "breadcrumbs"
        private const val PATH_CARD = "path"
      }

      private val cardLayout = CardLayout()
      private val contentPanel = JPanel(cardLayout)
      private val tree = Tree()

      init {

        val descriptorCopy = FileChooserDescriptor(descriptor)
        descriptorCopy.putUserData(FileTreeModel.SYSTEM_ROOTS_FILTER,
                                   Predicate { path: Path? -> roots.contains(path) })

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        fileTree = FileSystemTreeImpl(project, descriptorCopy, tree, null, null, null)
        fileTree.addOkAction(okAction)
        fileTree.addListener(FileSystemTree.Listener { selection -> updateBreadcrumbs(selection) }, disposable)
        val scrollPane = ScrollPaneFactory.createScrollPane(fileTree.tree)

        val toolbar = createToolbar()
        barPanel.border = UIUtil.getTextFieldBorder()
        pathTextField.field.border = JBUI.Borders.empty()
        barPanel.add(breadcrumbs, BREADCRUMBS_CARD)
        barPanel.add(pathTextField.field, PATH_CARD)
        breadcrumbs.onSelect { crumb, event ->
          val fileCrumb = crumb as? FileCrumb ?: return@onSelect
          if (fileCrumb == currentCrumbs.lastOrNull() && fileCrumb.file.isDirectory) {
            showDirectoryPopup(fileCrumb.file, event as? MouseEvent ?: return@onSelect)
          }
          else {
            fileTree.select(fileCrumb.file, null)
          }
        }
        breadcrumbs.addMouseListener(object : MouseAdapter() {
          override fun mouseClicked(e: MouseEvent) {
            if (breadcrumbs.getCrumbAt(e.x, e.y) == null) {
              switchToEditMode()
            }
          }
        })
        pathTextField.field.addKeyListener(object : KeyAdapter() {
          override fun keyPressed(e: KeyEvent) {
            when (e.keyCode) {
              KeyEvent.VK_ENTER -> { navigateToTextFieldPath(); e.consume() }
              KeyEvent.VK_ESCAPE -> { switchToBreadcrumbs(); e.consume() }
            }
          }
        })

        val loadingLabel = JBLabel(IdeBundle.message("universal.file.chooser.label.loading"), SwingConstants.CENTER)
        contentPanel.add(loadingLabel, LOADING_CARD)
        contentPanel.add(scrollPane, TREE_CARD)

        val mainPanel = panel {
          row {
            cell(barPanel)
              .align(AlignX.FILL)
          }
          row {
            cell(toolbar.component)
          }
          row {
            cell(contentPanel)
              .align(AlignX.FILL)
              .align(AlignY.FILL)
              .resizableColumn()
          }.resizableRow()
        }

        toolbar.targetComponent = mainPanel
        topComponent = mainPanel
        topComponent.putUserData(FILE_VIEW_KEY, this)

        loadRoots()

      }

      private fun loadRoots() {
        cardLayout.show(contentPanel, LOADING_CARD)
        scope.launch {
          withContext(Dispatchers.IO) {
            val elements = contributor.getRoots()
            runOnEdt {
              roots.clear()
              roots.addAll(elements)
              ((tree.model as AsyncTreeModel).model as FileTreeModel).resetRoots()
              cardLayout.show(contentPanel, TREE_CARD)
              fileToSelect?.let { fileTree.select(it, null) }
            }
          }
        }
      }

      fun navigateToHome() {
        topComponent.cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        scope.launch {
          withContext(Dispatchers.IO) {
            val basePath = fileTree.selectedFile?.let { runCatching { it.toNioPath() }.getOrNull() }
                           ?: contributor.getRoots().firstOrNull()
                           ?: return@withContext
            val homePath = basePath.asEelPath().descriptor.toEelApi().userInfo.home.asNioPath()
            val vFile = VfsUtil.findFile(homePath, true)
            runOnEdt {
              fileTree.select(vFile) { fileTree.expand(vFile, null) }
              topComponent.cursor = Cursor.getDefaultCursor()
            }
          }
        }
      }

      private fun createToolbar(): ActionToolbar {
        val showHiddenAction = object : ToggleAction(
          IdeBundle.message("universal.file.chooser.action.show.hidden.text"),
          IdeBundle.message("universal.file.chooser.action.show.hidden.description"),
          AllIcons.Actions.ToggleVisibility
        ) {
          override fun isSelected(e: AnActionEvent): Boolean = fileTree.areHiddensShown()

          override fun setSelected(e: AnActionEvent, state: Boolean) {
            fileTree.showHiddens(state)
          }

          override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        }

        val createDirectoryAction = object : AnAction(
          IdeBundle.message("universal.file.chooser.action.create.directory.text"),
          IdeBundle.message("universal.file.chooser.action.create.directory.description"),
          AllIcons.Actions.NewFolder
        ) {
          override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

          override fun update(e: AnActionEvent) {
            val parent = fileTree.getNewFileParent()
            val nioPath = parent?.let { runCatching { it.toNioPath() }.getOrNull() }
            e.presentation.isEnabled = nioPath != null && Files.isDirectory(nioPath) && Files.isWritable(nioPath)
          }

          override fun actionPerformed(e: AnActionEvent) {
            val parent = fileTree.getNewFileParent() ?: return
            val newFolderName = Messages.showInputDialog(
              UIBundle.message("create.new.folder.enter.new.folder.name.prompt.text"),
              UIBundle.message("new.folder.dialog.title"),
              Messages.getQuestionIcon(),
              "",
              null
            ) ?: return
            val failReason = fileTree.createNewFolder(parent, newFolderName)
            if (failReason != null) {
              Messages.showMessageDialog(
                UIBundle.message("create.new.folder.could.not.create.folder.error.message", newFolderName),
                UIBundle.message("error.dialog.title"),
                Messages.getErrorIcon()
              )
            }
          }
        }

        val deleteAction = object : AnAction(
          IdeBundle.message("universal.file.chooser.action.delete.text"),
          IdeBundle.message("universal.file.chooser.action.delete.description"),
          AllIcons.General.Delete
        ) {
          override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

          override fun update(e: AnActionEvent) {
            val selected = fileTree.selectedFile
            val nioPath = selected?.let { runCatching { it.toNioPath() }.getOrNull() }
            if (nioPath == null || roots.contains(nioPath) || !Files.isWritable(nioPath)) { e.presentation.isEnabled = false; return }
            if (Files.isDirectory(nioPath)) {
              val empty = runCatching { Files.list(nioPath).use { !it.findFirst().isPresent } }.getOrDefault(false)
              if (!empty) { e.presentation.isEnabled = false; return }
            }
            e.presentation.isEnabled = true
          }

          override fun actionPerformed(e: AnActionEvent) {
            val selected = fileTree.selectedFile ?: return
            val parent = selected.parent ?: return
            val nioPath = runCatching { selected.toNioPath() }.getOrNull() ?: return
            if (Messages.showYesNoDialog(
                IdeBundle.message("universal.file.chooser.action.delete.confirm", selected.name),
                IdeBundle.message("universal.file.chooser.action.delete.text"),
                Messages.getWarningIcon()
              ) != Messages.YES) return

            scope.launch {
              withContext(Dispatchers.IO) {
                val result = runCatching { Files.delete(nioPath) }
                if (result.isSuccess) {
                  (parent as? NewVirtualFile)?.markDirtyRecursively()
                  RefreshQueue.getInstance().refresh(
                    true, false, null,
                    ModalityState.stateForComponent(fileTree.tree), parent
                  )
                }
                else {
                  runOnEdt {
                    val message = result.exceptionOrNull()?.message ?: ""
                    Messages.showErrorDialog(message, IdeBundle.message("universal.file.chooser.action.delete.text"))
                  }
                }
              }
            }
          }
        }

        val refreshAction = object : AnAction(
          IdeBundle.message("universal.file.chooser.action.refresh.text"),
          IdeBundle.message("universal.file.chooser.action.refresh.description"),
          AllIcons.Actions.Refresh
        ) {
          override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

          override fun actionPerformed(e: AnActionEvent) {
            val selected = fileTree.selectedFile
            val directory = if (selected != null && selected.isDirectory) selected else selected?.parent
            val filesToRefresh = if (directory != null) arrayOf(directory) else ManagingFS.getInstance().localRoots
            for (file in filesToRefresh) {
              (file as? NewVirtualFile)?.markDirtyRecursively()
            }
            RefreshQueue.getInstance().refresh(true, true, null, ModalityState.stateForComponent(fileTree.tree), *filesToRefresh)
          }
        }

        val actionGroup = DefaultActionGroup().apply {
          add(showHiddenAction)
          add(createDirectoryAction)
          add(deleteAction)
          add(refreshAction)
        }

        return ActionManager.getInstance().createActionToolbar("UniversalFileChooserToolbar", actionGroup, true)
      }

      fun getSelectedFiles(): List<VirtualFile> {
        return fileTree.selectedFiles.asList()
      }

      private fun switchToEditMode() {
        val selectedFile = fileTree.selectedFile
        val path = selectedFile?.let { runCatching { it.toNioPath().toString() }.getOrNull() } ?: selectedFile?.path ?: ""
        pathTextField.field.text = path
        barCardLayout.show(barPanel, PATH_CARD)
        pathTextField.field.requestFocusInWindow()
        pathTextField.field.selectAll()
      }

      private fun switchToBreadcrumbs() {
        barCardLayout.show(barPanel, BREADCRUMBS_CARD)
        fileTree.tree.requestFocusInWindow()
      }

      private fun navigateToTextFieldPath() {
        val text = pathTextField.field.text.trim()
        switchToBreadcrumbs()
        if (text.isEmpty()) return
        scope.launch {
          withContext(Dispatchers.IO) {
            val path = runCatching { Path.of(text) }.getOrNull() ?: return@withContext
            val vFile = VfsUtil.findFile(path, true)
            runOnEdt {
              if (vFile != null) {
                fileTree.select(vFile) { fileTree.expand(vFile, null) }
              }
            }
          }
        }
      }

      private fun updateBreadcrumbs(selection: List<VirtualFile>) {
        switchToBreadcrumbs()
        val file = selection.firstOrNull()
        if (file == null) {
          currentCrumbs = emptyList()
          breadcrumbs.setCrumbs(emptyList())
          return
        }
        val crumbs = mutableListOf<FileCrumb>()
        var current: VirtualFile? = file
        while (current != null) {
          crumbs.add(0, FileCrumb(current))
          current = current.parent
        }
        currentCrumbs = crumbs
        breadcrumbs.setCrumbs(crumbs)
      }

      private fun showDirectoryPopup(directory: VirtualFile, event: MouseEvent) {
        val showHidden = fileTree.areHiddensShown()
        scope.launch {
          withContext(Dispatchers.IO) {
            val children = directory.children
              .filter { it.isDirectory && (showHidden || !descriptor.isHidden(it)) }
              .sortedBy { it.name.lowercase() }
            if (!children.isEmpty()) {
              runOnEdt {
                JBPopupFactory.getInstance()
                  .createPopupChooserBuilder(children)
                  .setRenderer(listCellRenderer("") {
                    icon(AllIcons.Nodes.Folder)
                    text(value.name)
                  })
                  .setItemChosenCallback { chosen -> fileTree.select(chosen) { fileTree.expand(chosen, null) } }
                  .createPopup()
                  .show(RelativePoint(event))
              }
            }
          }
        }
      }

      private class FileCrumb(val file: VirtualFile) : Crumb {
        @NlsSafe override fun getText(): String = file.name.ifEmpty { file.path }
        @NlsSafe override fun getTooltip(): String = file.path
      }
    }
  }

  @Suppress("ForbiddenInSuspectContextMethod") // ModalityState.any() is required.
  private fun runOnEdt(runnable: Runnable) {
    ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any())
  }
}



