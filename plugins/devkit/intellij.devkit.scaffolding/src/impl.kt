// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.devkit.scaffolding

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.newclass.CreateWithTemplatesDialogPanel
import com.intellij.ide.plugins.ModuleLoadingRule
import com.intellij.ide.ui.newItemPopup.NewItemPopupUtil
import com.intellij.lang.LangBundle
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtil
import com.intellij.openapi.project.IntelliJProjectUtil.isIntelliJPlatformProject
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.xml.XmlFile
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Consumer
import com.intellij.util.ui.JBUI
import com.intellij.workspaceModel.ide.legacyBridge.impl.java.JAVA_MODULE_ENTITY_TYPE_ID_NAME
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.TestOnly
import org.jetbrains.idea.devkit.dom.index.PluginIdDependenciesIndex
import org.jetbrains.idea.devkit.util.DescriptorUtil
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import java.awt.event.InputEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JList
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import kotlin.coroutines.resume
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal fun isActionEnabled(dc: DataContext): Boolean {
  val project = dc.getData(CommonDataKeys.PROJECT)
                ?: return false
  val virtualFile = dc.getData(CommonDataKeys.VIRTUAL_FILE)
                    ?: return false
  if (!isIntelliJPlatformProject(project)) {
    return false
  }
  return !ProjectFileIndex.getInstance(project).isInSource(virtualFile)
}

internal suspend fun askForModule(project: Project, popupContext: NewIjModuleCreationContext): NewIjModuleRequest {
  val contentPanel = NewIjModulePopupPanel(popupContext)
  val nameField: JTextField = contentPanel.nameField
  val popup: JBPopup = NewItemPopupUtil.createNewItemPopup(message("scaffolding.new.ij.module"), contentPanel, nameField)
  return suspendCancellableCoroutine { continuation ->
    contentPanel.applyAction = Consumer { event: InputEvent? ->
      val name = contentPanel.enteredName
      if (name.isBlank()) {
        contentPanel.setError(LangBundle.message("incorrect.name"))
      }
      else {
        popup.closeOk(event)
        continuation.resume(NewIjModuleRequest(name, IjModuleKind.fromTemplateName(contentPanel.selectedTemplate)))
      }
    }
    popup.addListener(object : JBPopupListener {
      override fun onClosed(event: LightweightWindowEvent) {
        if (!event.isOk) {
          continuation.cancel()
        }
      }
    })
    continuation.invokeOnCancellation {
      popup.cancel()
    }
    popup.showCenteredInCurrentWindow(project)
  }
}

internal data class NewIjModuleCreationContext(
  val suggestedNamePrefix: String,
  val targetPlugin: ContentModuleRegistrationTarget?,
)

private class NewIjModulePopupPanel(private val popupContext: NewIjModuleCreationContext) :
  CreateWithTemplatesDialogPanel(IjModuleKind.matchingTemplate(popupContext.suggestedNamePrefix).templateName,
                                 IjModuleKind.entries.map { it.presentation }) {

  private val suggestionsByKind = IjModuleKind.entries.associateWithTo(LinkedHashMap()) { kind ->
    kind.defaultSuggestion(popupContext.suggestedNamePrefix)
  }
  private var suppressDocumentTracking = false
  private var selectedKind = IjModuleKind.matchingTemplate(popupContext.suggestedNamePrefix)

  init {
    myTemplatesList.cellRenderer = NewIjModuleKindRenderer(popupContext.targetPlugin?.pluginId)
    restoreSuggestion(selectedKind)

    setTemplateSelectorMatcher { moduleName, template ->
      IjModuleKind.matchingTemplate(moduleName).templateName == template.templateName()
    }

    nameField.document.addDocumentListener(object : DocumentAdapter() {
      override fun textChanged(e: DocumentEvent) {
        if (!suppressDocumentTracking) {
          suggestionsByKind[selectedKind] = nameField.text
        }
      }
    })

    myTemplatesList.addListSelectionListener {
      if (!it.valueIsAdjusting) {
        applySuggestionForSelectionChange()
      }
    }
  }

  private fun applySuggestionForSelectionChange() {
    val newKind = currentKind() ?: return
    if (newKind == selectedKind) {
      return
    }

    suggestionsByKind[selectedKind] = nameField.text
    selectedKind = newKind
    restoreSuggestion(newKind)
  }

  private fun restoreSuggestion(kind: IjModuleKind) {
    val suggestion = suggestionsByKind.getValue(kind)
    if (nameField.text == suggestion) return
    suppressDocumentTracking = true
    nameField.text = suggestion
    suppressDocumentTracking = false
  }

  private fun currentKind(): IjModuleKind? {
    val selectedTemplate = myTemplatesList.selectedValue ?: return null
    return IjModuleKind.fromTemplateName(selectedTemplate.templateName())
  }
}

private class NewIjModuleKindRenderer(
  private val contentModuleRegistrationPluginId: String?,
) : ColoredListCellRenderer<CreateWithTemplatesDialogPanel.TemplatePresentation>() {

  @Suppress("HardCodedStringLiteral")
  override fun customizeCellRenderer(
    list: JList<out CreateWithTemplatesDialogPanel.TemplatePresentation>,
    value: CreateWithTemplatesDialogPanel.TemplatePresentation?,
    index: Int,
    selected: Boolean,
    hasFocus: Boolean,
  ) {
    if (value == null) return

    border = JBUI.Borders.empty(3, 6, 3, 1)
    icon = value.icon()
    append(value.kind())

    val moduleKind = IjModuleKind.fromTemplateName(value.templateName())
    if (moduleKind != IjModuleKind.EMPTY && !contentModuleRegistrationPluginId.isNullOrBlank()) {
      append(" (target plugin ID=", SimpleTextAttributes.GRAYED_ATTRIBUTES)
      append("`$contentModuleRegistrationPluginId`", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
      append(")", SimpleTextAttributes.GRAYED_ATTRIBUTES)
    }
  }
}

internal fun collectNewIjModuleCreationContext(newModuleParentDirectory: VirtualFile, project: Project): NewIjModuleCreationContext {
  return NewIjModuleCreationContext(
    suggestedNamePrefix = suggestModuleNamePrefix(newModuleParentDirectory, project),
    targetPlugin = findSingleTargetPluginForModuleOrNull(project, newModuleParentDirectory, null),
  )
}

private fun suggestModuleNamePrefix(parentDir: VirtualFile, project: Project): String {
  val fileIndex = ProjectFileIndex.getInstance(project)
  val parentContentRoot = fileIndex.getContentRootForFile(parentDir)
  if (parentContentRoot == parentDir) {
    val module = fileIndex.getModuleForFile(parentContentRoot)
    if (module != null) {
      return "${module.name.removeSuffix(".plugin").removeSuffix(".main").removeSuffix(".core")}."
    }
  }
  if (parentDir.isDirectory) {
    val siblingModuleNames = parentDir.children
      .asSequence()
      .filter { fileIndex.getContentRootForFile(it) == it }
      .mapNotNull { fileIndex.getModuleForFile(it)?.name }
    val commonPrefix = siblingModuleNames.reduceOrNull { acc, item -> acc.commonPrefixWith(item) } ?: ""
    val suggestion = commonPrefix.substringBeforeLast('.', "")
    if (suggestion.isNotEmpty()) {
      return "$suggestion."
    }
  }
  return "intellij."
}

internal suspend fun createIjModule(
  project: Project,
  newModuleParentDirectory: VirtualFile,
  moduleName: String,
  kind: IjModuleKind,
  targetPlugin: ContentModuleRegistrationTarget?,
): CreatedIjModule {
  return createIjModule(project, newModuleParentDirectory, NewIjModuleRequest(moduleName, kind), targetPlugin)
}

internal suspend fun createIjModule(
  project: Project,
  newModuleParentDirectory: VirtualFile,
  request: NewIjModuleRequest,
  targetPlugin: ContentModuleRegistrationTarget?,
): CreatedIjModule {
  val normalizedRequest = request.normalized()
  val directoryName = computeDirectoryNameForModule(normalizedRequest.moduleName, newModuleParentDirectory, project)
  val createdModule = when (normalizedRequest.kind) {
    IjModuleKind.EMPTY -> {
      val files = prepareEmptyModuleFiles(newModuleParentDirectory.toNioPath(), normalizedRequest.moduleName, directoryName)
      val vFiles = files.toVFiles()
                   ?: error("Failed to locate created module files in VFS")
      backgroundWriteAction {
        val module =
          ModuleManager.getInstance(project).newModule(
            files.moduleRoot.resolve("${normalizedRequest.moduleName}.iml"),
            JAVA_MODULE_ENTITY_TYPE_ID_NAME,
          )
        val rootModel = ModuleRootManager.getInstance(module).modifiableModel
        rootModel.inheritSdk()
        rootModel.addContentEntry(vFiles.vModuleRoot).also { contentEntry ->
          contentEntry.addSourceFolder(vFiles.vSrc, JavaSourceRootType.SOURCE).also {
            it.packagePrefix = "com.${normalizedRequest.moduleName}"
          }
          contentEntry.addSourceFolder(vFiles.vResources, JavaResourceRootType.RESOURCE)
        }
        rootModel.commit()
      }
      CreatedIjModule(normalizedRequest.moduleName, files.moduleRoot, normalizedRequest.kind, existedBefore = false)
    }
    else -> {
      val moduleRoot =
        prepareTemplateModuleFiles(project, newModuleParentDirectory, normalizedRequest.moduleName, directoryName, normalizedRequest.kind)
      CreatedIjModule(normalizedRequest.moduleName, moduleRoot.moduleRoot, normalizedRequest.kind, existedBefore = moduleRoot.existedBefore)
    }
  }
  if (normalizedRequest.kind != IjModuleKind.EMPTY && targetPlugin != null) {
    addModuleToEnclosingPluginIfPresent(project, targetPlugin, createdModule)
  }
  project.scheduleSave() // to write changes in modules.xml to the disk
  return createdModule
}

@TestOnly
suspend fun addModuleToEnclosingPluginIfPresentForTests(
  project: Project,
  root: VirtualFile,
  moduleName: String,
  kindTemplateName: String,
) {
  val targetPlugin = readAction {
    findSingleTargetPluginForModuleOrNull(project, root, moduleName)
  } ?: return
  addModuleToEnclosingPluginIfPresent(
    project,
    targetPlugin,
    CreatedIjModule(moduleName, root.toNioPath(), IjModuleKind.fromTemplateName(kindTemplateName), existedBefore = false),
  )
}

@TestOnly
suspend fun findEnclosingPluginXmlForTests(project: Project, root: VirtualFile, moduleName: String): XmlFile? {
  return readAction {
    findSingleTargetPluginForModuleOrNull(project, root, moduleName)?.pluginXml
  }
}

private suspend fun computeDirectoryNameForModule(moduleName: String, parentDir: VirtualFile, project: Project): String {
  return withContext(Dispatchers.IO) {
    readAction {
      val fileIndex = ProjectFileIndex.getInstance(project)
      val parentDirectoryNames = generateSequence(parentDir) { it.parent }
        .takeWhile { it.parent != null && fileIndex.isInProjectOrExcluded(it.parent) }
        .mapTo(HashSet()) { it.name }
      moduleName.split('.').dropWhile { it in parentDirectoryNames || it == "intellij" }.joinToString(".")
    }
  }
}

private suspend fun prepareEmptyModuleFiles(root: Path, moduleName: String, directoryName: String): ModuleFiles =
  withContext(Dispatchers.IO) {
    val moduleRoot = root.resolve(directoryName).createDirectories()
    val src = moduleRoot.resolve("src").createDirectories().also { src ->
      src.resolve(".keep").createFile() // empty file so that `src` exists in git
    }
    val resources = moduleRoot.resolve("resources").createDirectories().also { resources ->
      resources.resolve("$moduleName.xml").writeText("<idea-plugin>\n</idea-plugin>\n")
    }
    ModuleFiles(moduleRoot, src, resources)
  }

private suspend fun prepareTemplateModuleFiles(
  project: Project,
  vRoot: VirtualFile,
  moduleName: String,
  directoryName: String,
  kind: IjModuleKind,
): PreparedTemplateModule {
  return withContext(Dispatchers.IO) {
    val templateRoot = project.templateRoot(kind)
    val maybeExistingModule = ModuleManager.getInstance(project).findModuleByName(moduleName)
    if (maybeExistingModule != null) {
      thisLogger().warn("Target module already exists: $moduleName")
      return@withContext PreparedTemplateModule(maybeExistingModule.moduleFile!!.parent.toNioPath(), existedBefore = true)
    }
    val targetModulePath = vRoot.toNioPath().resolve(directoryName)
    if (targetModulePath.exists()) {
      thisLogger().warn("Target module path already exists: $targetModulePath")
      return@withContext PreparedTemplateModule(targetModulePath, existedBefore = false)
    }
    val moduleRoot = targetModulePath.createDirectory()
    val replacementContext = ReplacementContext.create(project, vRoot, moduleName, kind)

    Files.walk(templateRoot).use { paths ->
      for (source in paths.toList().sortedBy { it.nameCount }) {
        if (source == templateRoot) continue

        val relativePath = templateRoot.relativize(source).toSystemIndependentString()
        val target = moduleRoot.resolve(replacementContext.apply(relativePath))
        if (Files.isDirectory(source)) {
          target.createDirectories()
        }
        else {
          target.parent?.createDirectories()
          target.writeText(replacementContext.apply(source.readText()))
        }
      }
    }

    VfsUtil.findFile(moduleRoot, true)
    val moduleFile = moduleRoot.resolve("$moduleName.iml")
    backgroundWriteAction {
      ModuleManager.getInstance(project).loadModule(moduleFile)
    }

    PreparedTemplateModule(moduleRoot, existedBefore = false)
  }
}

private class ModuleFiles(
  val moduleRoot: Path,
  val src: Path,
  val resources: Path,
)

private class PreparedTemplateModule(
  val moduleRoot: Path,
  val existedBefore: Boolean,
)

internal data class CreatedIjModule(
  val moduleName: String,
  val moduleRoot: Path,
  val moduleKind: IjModuleKind,
  val existedBefore: Boolean,
)

internal suspend fun openXmlDescriptorIfPresent(project: Project, createdModule: CreatedIjModule) {
  val descriptor = readAction {
    findXmlDescriptor(project, createdModule.moduleName)
    ?: findXmlDescriptor(project, createdModule.moduleRoot, createdModule.moduleName)
  } ?: return

  withContext(Dispatchers.EDT) {
    OpenFileDescriptor(project, descriptor.virtualFile).navigate(true)
  }
}

internal fun notifyModuleAlreadyExists(project: Project, createdModule: CreatedIjModule) {
  NotificationGroupManager.getInstance()
    .getNotificationGroup("DevKit Errors")
    .createNotification(
      message("scaffolding.module.kind.already.exists.title"),
      message("scaffolding.module.kind.already.exists.message", createdModule.moduleKind.displayName, createdModule.moduleName),
      NotificationType.INFORMATION,
    )
    .notify(project)
}

private suspend fun ModuleFiles.toVFiles(): ModuleVFiles? = withContext(Dispatchers.IO) {
  ModuleVFiles(
    vModuleRoot = VfsUtil.findFile(moduleRoot, true) ?: return@withContext null,
    vSrc = VfsUtil.findFile(src, true) ?: return@withContext null,
    vResources = VfsUtil.findFile(resources, true) ?: return@withContext null,
  )
}

private class ModuleVFiles(
  val vModuleRoot: VirtualFile,
  val vSrc: VirtualFile,
  val vResources: VirtualFile,
)

internal data class NewIjModuleRequest(
  val moduleName: @NlsSafe String,
  val kind: IjModuleKind,
) {
  fun normalized(): NewIjModuleRequest {
    return copy(moduleName = kind.applyToModuleName(moduleName))
  }
}

internal enum class IjModuleKind(
  val templateName: String,
  private val titleKey: String,
  private val sampleSuffix: String? = null,
) {
  EMPTY("empty", "scaffolding.new.ij.module.kind.empty"),
  FRONTEND("frontend", "scaffolding.new.ij.module.kind.frontend", "frontend"),
  BACKEND("backend", "scaffolding.new.ij.module.kind.backend", "backend"),
  SHARED("shared", "scaffolding.new.ij.module.kind.shared", "shared");

  val presentation: CreateWithTemplatesDialogPanel.TemplatePresentation
    get() = CreateWithTemplatesDialogPanel.TemplatePresentation(message(titleKey), icon, templateName)

  val displayName: @Nls String
    get() = message(titleKey)

  val sampleDirectoryName: String?
    get() = sampleSuffix?.let { "$SAMPLE_MODULE_STEM.$it" }

  val moduleSuffix: String?
    get() = sampleSuffix

  fun applyToModuleName(moduleName: String): @NlsSafe String {
    if (moduleSuffix == null) return moduleName.trim()

    val trimmedName = moduleName.trim()
    if (trimmedName.isEmpty()) return trimmedName

    val withoutTrailingDot = trimmedName.trimEnd('.')
    val withoutKnownSuffix = withoutTrailingDot.removeKnownKindSuffix()
    return "$withoutKnownSuffix.$moduleSuffix"
  }

  fun defaultSuggestion(moduleName: String): @NlsSafe String {
    val baseSuggestion = moduleName.baseSuggestion()
    return when (this) {
      EMPTY -> baseSuggestion
      else -> applyToModuleName(baseSuggestion)
    }
  }

  companion object {
    fun fromTemplateName(templateName: String): IjModuleKind {
      return entries.first { it.templateName == templateName }
    }

    fun matchingTemplate(moduleName: String): IjModuleKind {
      return entries.firstOrNull { kind ->
        kind.moduleSuffix != null && moduleName.endsWith(".${kind.moduleSuffix}")
      } ?: EMPTY
    }
  }
}

private fun String.removeKnownKindSuffix(): String {
  for (kind in IjModuleKind.entries) {
    val suffix = kind.moduleSuffix ?: continue
    if (endsWith(".$suffix")) {
      return removeSuffix(".$suffix")
    }
  }
  return this
}

private fun String.baseSuggestion(): String {
  val trimmed = trim()
  if (trimmed.isEmpty()) return trimmed

  val hasTrailingDot = trimmed.endsWith('.')
  val withoutTrailingDot = trimmed.trimEnd('.')
  val withoutKindSuffix = withoutTrailingDot.removeKnownKindSuffix()
  return if (hasTrailingDot) "$withoutKindSuffix." else withoutKindSuffix
}

private val IjModuleKind.icon
  get() = when (this) {
    IjModuleKind.EMPTY -> AllIcons.Nodes.Module
    IjModuleKind.FRONTEND -> AllIcons.Nodes.Plugin
    IjModuleKind.BACKEND -> AllIcons.Nodes.Plugin
    IjModuleKind.SHARED -> AllIcons.Nodes.Plugin
  }

private data class ReplacementContext(private val replacements: List<Pair<String, String>>) {
  fun apply(value: String): String {
    return replacements.fold(value) { current, (from, to) -> current.replace(from, to) }
  }

  companion object {
    suspend fun create(
      project: Project,
      vRoot: VirtualFile,
      moduleName: String,
      kind: IjModuleKind,
    ): ReplacementContext {
      val templateSuffix = kind.moduleSuffix ?: error("Replacement context is only available for template-backed module kinds")
      val shortModuleName = moduleName.removePrefix(INTELLIJ_PREFIX)
      val familyShortName = shortModuleName.removeSuffix(".$templateSuffix")
      val sharedShortName = if (kind == IjModuleKind.SHARED) shortModuleName else "$familyShortName.shared"
      val sharedModuleName = if (kind == IjModuleKind.SHARED) moduleName else qualifyModuleName(sharedShortName, moduleName)
      val sharedDirectoryName = computeDirectoryNameForModule(sharedModuleName, vRoot, project)
      val sharedLabel = project.toBazelLabel(vRoot.toNioPath().resolve(sharedDirectoryName))

      return ReplacementContext(
        listOf(
          "//.remdev/$SAMPLE_MODULE_STEM.shared" to sharedLabel,
          "intellij.$SAMPLE_MODULE_STEM.$templateSuffix" to moduleName,
          "$SAMPLE_MODULE_STEM.$templateSuffix" to shortModuleName,
          "intellij.$SAMPLE_MODULE_STEM.shared" to sharedModuleName,
          "$SAMPLE_MODULE_STEM.shared" to sharedShortName,
        ),
      )
    }
  }
}

private fun qualifyModuleName(shortModuleName: String, moduleName: String): String {
  return if (moduleName.startsWith(INTELLIJ_PREFIX)) "$INTELLIJ_PREFIX$shortModuleName" else shortModuleName
}

private fun Project.toBazelLabel(moduleRoot: Path): String {
  val projectBasePath = basePath ?: error("Project base path is unavailable")
  val relativePath = Path.of(projectBasePath).toAbsolutePath().normalize()
    .relativize(moduleRoot.toAbsolutePath().normalize())
    .toSystemIndependentString()
  return "//$relativePath"
}

private fun Project.templateRoot(kind: IjModuleKind): Path {
  val projectBasePath = basePath ?: error("Project base path is unavailable")
  val templateDirectoryName = kind.sampleDirectoryName ?: error("Template root is only available for template-backed module kinds")
  return Path.of(projectBasePath).resolve(TEMPLATE_MODULES_ROOT).resolve(templateDirectoryName)
}

private suspend fun addModuleToEnclosingPluginIfPresent(
  project: Project,
  targetPlugin: ContentModuleRegistrationTarget,
  createdModule: CreatedIjModule,
) {
  val ideaPlugin = readAction { DescriptorUtil.getIdeaPlugin(targetPlugin.pluginXml) } ?: return
  withContext(Dispatchers.EDT) {
    CommandProcessor.getInstance().runUndoTransparentAction {
      ApplicationManager.getApplication().runWriteAction {
        val isAlreadyRegistered = ideaPlugin.content
          .asSequence()
          .flatMap { it.moduleEntry.asSequence() }
          .any { it.name.stringValue == createdModule.moduleName }
        if (!isAlreadyRegistered) {
          val moduleEntry = ideaPlugin.firstOrAddContentDescriptor.addModuleEntry()
          moduleEntry.name.stringValue = createdModule.moduleName
          when (createdModule.moduleKind) {
            IjModuleKind.EMPTY -> {}
            IjModuleKind.SHARED -> {
              moduleEntry.loading.stringValue = ModuleLoadingRule.REQUIRED.name.lowercase()
            }
            IjModuleKind.FRONTEND -> {
              moduleEntry.requiredIfAvailable.stringValue = PLATFORM_FRONTEND_MODULE
            }
            IjModuleKind.BACKEND -> {
              moduleEntry.requiredIfAvailable.stringValue = PLATFORM_BACKEND_MODULE
            }
          }
          val xmlElement = moduleEntry.parent.xmlElement
          if (xmlElement != null) {
            CodeStyleManager.getInstance(project).reformat(xmlElement)
          }
        }
      }
    }
  }
}

private fun findSingleTargetPluginForModuleOrNull(
  project: Project,
  newModuleParentDirectory: VirtualFile,
  newModuleNameToIgnore: String?,
): ContentModuleRegistrationTarget? {
  return findPluginInModuleParentDirectory(project, newModuleParentDirectory)
         ?: findPluginBySiblingModules(project, newModuleParentDirectory, newModuleNameToIgnore)
}

private fun findPluginInModuleParentDirectory(project: Project, newModuleParentDirectory: VirtualFile): ContentModuleRegistrationTarget? {
  return newModuleParentDirectory.findPluginXml(project)?.toContentModuleRegistrationTarget()
}

private fun findPluginBySiblingModules(
  project: Project,
  newModuleParentDirectory: VirtualFile,
  newModuleNameToIgnore: String?,
): ContentModuleRegistrationTarget? {
  if (!newModuleParentDirectory.isDirectory) return null

  val candidates = mutableSetOf<XmlFile>()
  val pluginXmlCandidateSequence = newModuleParentDirectory.children
    .asSequence()
    .filter { it.isDirectory }
    .mapNotNull { ModuleUtil.findModuleForFile(it, project) }
    .filter { newModuleNameToIgnore == null || it.name != newModuleNameToIgnore }
    .distinct()

  for (module in pluginXmlCandidateSequence) {
    for (moduleDescriptor in findModuleDescriptorFiles(project, module)) {
      for (pluginXml in findPluginXmlsIncludingContentModule(project, moduleDescriptor)) {
        candidates += pluginXml

        if (candidates.size > 1) {
          val moduleName = newModuleNameToIgnore ?: "<unknown>"
          LOG.warn(
            "Ambiguous plugin.xml owners for new content module '$moduleName' under '${newModuleParentDirectory.path}': " +
            candidates.joinToString { pluginXml -> pluginXml.virtualFile.path }
          )
          return null
        }
      }
    }
  }

  return candidates.singleOrNull()?.toContentModuleRegistrationTarget()
}

private fun findModuleDescriptorFiles(project: Project, module: com.intellij.openapi.module.Module): Sequence<XmlFile> {
  val psiManager = PsiManager.getInstance(project)
  return ModuleRootManager.getInstance(module)
    .getSourceRoots(JavaModuleSourceRootTypes.RESOURCES)
    .asSequence()
    .flatMap { resourceRoot ->
      resourceRoot.children
        .asSequence()
        .filter { !it.isDirectory && it.extension == "xml" }
    }
    .mapNotNull { psiManager.findFile(it) as? XmlFile }
    .filter { DescriptorUtil.isPluginModuleFile(it) }
}

private fun findPluginXmlsIncludingContentModule(project: Project, moduleDescriptor: XmlFile): Sequence<XmlFile> {
  val moduleVirtualFile = moduleDescriptor.virtualFile ?: return emptySequence()
  val moduleName = moduleVirtualFile.nameWithoutExtension
  val psiManager = PsiManager.getInstance(project)
  return PluginIdDependenciesIndex.findDependsTo(project, moduleVirtualFile)
    .asSequence()
    .mapNotNull { psiManager.findFile(it) as? XmlFile }
    .filter { xmlFile ->
      val ideaPlugin = DescriptorUtil.getIdeaPlugin(xmlFile) ?: return@filter false
      ideaPlugin.content
        .asSequence()
        .flatMap { it.moduleEntry.asSequence() }
        .any { it.name.stringValue == moduleName }
    }
    .distinctBy { it.virtualFile.path }
}

private fun XmlFile.toContentModuleRegistrationTarget(): ContentModuleRegistrationTarget? {
  val ideaPlugin = DescriptorUtil.getIdeaPlugin(this) ?: return null
  return ContentModuleRegistrationTarget(this, ideaPlugin.pluginId ?: "no plugin id")
}

private fun findXmlDescriptor(project: Project, moduleName: String): XmlFile? {
  val module = ModuleManager.getInstance(project).findModuleByName(moduleName) ?: return null
  val psiManager = PsiManager.getInstance(project)
  for (sourceRoot in ModuleRootManager.getInstance(module).getSourceRoots(false)) {
    val moduleXml = sourceRoot.findChild("$moduleName.xml")
    if (moduleXml != null && !moduleXml.isDirectory) {
      val psiFile = psiManager.findFile(moduleXml)
      if (psiFile is XmlFile) return psiFile
    }

    val pluginXml = sourceRoot.findChild("META-INF")?.findChild("plugin.xml")
    if (pluginXml != null && !pluginXml.isDirectory) {
      val psiFile = psiManager.findFile(pluginXml)
      if (psiFile is XmlFile) return psiFile
    }
  }
  return null
}

private fun findXmlDescriptor(project: Project, moduleRoot: Path, moduleName: String): XmlFile? {
  val psiManager = PsiManager.getInstance(project)
  val moduleRootFile = VfsUtil.findFile(moduleRoot, true) ?: return null
  val resourcesRoot = moduleRootFile.findChild("resources") ?: return null

  val moduleXml = resourcesRoot.findChild("$moduleName.xml")
  if (moduleXml != null && !moduleXml.isDirectory) {
    val psiFile = psiManager.findFile(moduleXml)
    if (psiFile is XmlFile) return psiFile
  }

  val pluginXml = resourcesRoot.findChild("META-INF")?.findChild("plugin.xml")
  if (pluginXml != null && !pluginXml.isDirectory) {
    val psiFile = psiManager.findFile(pluginXml)
    if (psiFile is XmlFile) return psiFile
  }

  return null
}

private fun VirtualFile.findPluginXml(project: Project): XmlFile? {
  val pluginXml = findChild("resources")
                    ?.findChild("META-INF")
                    ?.findChild("plugin.xml")
                  ?: return null
  val pluginXmlPsi = PsiManager.getInstance(project).findFile(pluginXml) as? XmlFile ?: return null
  return pluginXmlPsi.takeIf { DescriptorUtil.getIdeaPlugin(it) != null }
}

private fun Path.toSystemIndependentString(): String {
  return invariantSeparatorsPathString
}

internal data class ContentModuleRegistrationTarget(
  val pluginXml: XmlFile,
  val pluginId: String,
)

private val LOG = logger<NewIjModuleAction>()

private const val SAMPLE_MODULE_STEM = "remDevFeatureSample"
private const val TEMPLATE_MODULES_ROOT = "plugins/.remdev"
private const val INTELLIJ_PREFIX = "intellij."
private const val PLATFORM_FRONTEND_MODULE = "intellij.platform.frontend"
private const val PLATFORM_BACKEND_MODULE = "intellij.platform.backend"
