// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlFile
import org.jetbrains.idea.devkit.DevKitBundle.message
import org.jetbrains.idea.devkit.dom.IdeaPlugin
import org.jetbrains.idea.devkit.module.PluginModuleType
import org.jetbrains.idea.devkit.util.DescriptorUtil

internal object SplitModeDependencyQuickFixes {
  fun createMismatchFixes(
    moduleName: String,
    moduleAnalysis: ModuleAnalysis,
    desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
  ): Array<LocalQuickFix> {
    if (desiredModuleKind != SplitModeApiRestrictionsService.ModuleKind.FRONTEND
        && desiredModuleKind != SplitModeApiRestrictionsService.ModuleKind.BACKEND) {
      return emptyArray()
    }

    val fixes = ArrayList<LocalQuickFix>()
    if (shouldOfferMakeOnlyDependenciesFix(moduleAnalysis.dependencyAnalysis, desiredModuleKind)) {
      fixes.add(MakeModuleOnlyKindDependenciesFix(moduleName, desiredModuleKind))
    }
    if (shouldOfferAddExplicitDependencyFix(moduleAnalysis.dependencyAnalysis, desiredModuleKind)) {
      fixes.add(AddExplicitPlatformDependencyFix(moduleName, desiredModuleKind))
    }
    fixes.add(AddExplicitPlatformDependencyFix(moduleName, SplitModeApiRestrictionsService.ModuleKind.MONOLITH))
    return fixes.toTypedArray()
  }

  fun createMixedModuleFixes(moduleName: String, dependencyAnalysis: DependencyAnalysis): Array<LocalQuickFix> {
    val fixes = ArrayList<LocalQuickFix>()
    if (dependencyAnalysis.declaresBackendDependencies) {
      fixes.add(MakeModuleOnlyKindDependenciesFix(moduleName, SplitModeApiRestrictionsService.ModuleKind.FRONTEND))
    }
    if (dependencyAnalysis.declaresFrontendDependencies) {
      fixes.add(MakeModuleOnlyKindDependenciesFix(moduleName, SplitModeApiRestrictionsService.ModuleKind.BACKEND))
    }
    fixes.add(AddExplicitPlatformDependencyFix(moduleName, SplitModeApiRestrictionsService.ModuleKind.MONOLITH))
    return fixes.toTypedArray()
  }

  fun createAddExplicitDependencyFix(moduleName: String, moduleKind: SplitModeApiRestrictionsService.ModuleKind): LocalQuickFix {
    return AddExplicitPlatformDependencyFix(moduleName, moduleKind)
  }

  private fun shouldOfferMakeOnlyDependenciesFix(
    dependencyAnalysis: DependencyAnalysis,
    desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
  ): Boolean {
    return when (desiredModuleKind) {
      SplitModeApiRestrictionsService.ModuleKind.FRONTEND -> dependencyAnalysis.declaresBackendDependencies
      SplitModeApiRestrictionsService.ModuleKind.BACKEND -> dependencyAnalysis.declaresFrontendDependencies
      SplitModeApiRestrictionsService.ModuleKind.MONOLITH,
      SplitModeApiRestrictionsService.ModuleKind.MIXED,
      SplitModeApiRestrictionsService.ModuleKind.SHARED,
        -> false
    }
  }

  private fun shouldOfferAddExplicitDependencyFix(
    dependencyAnalysis: DependencyAnalysis,
    desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
  ): Boolean {
    return when (desiredModuleKind) {
      SplitModeApiRestrictionsService.ModuleKind.FRONTEND -> {
        !dependencyAnalysis.declaresExplicitFrontendDependencies
        && !dependencyAnalysis.hasBackendDependencies
        && !dependencyAnalysis.declaresExplicitBackendDependencies
      }
      SplitModeApiRestrictionsService.ModuleKind.BACKEND -> {
        !dependencyAnalysis.declaresExplicitBackendDependencies
        && !dependencyAnalysis.hasFrontendDependencies
        && !dependencyAnalysis.declaresExplicitFrontendDependencies
      }
      SplitModeApiRestrictionsService.ModuleKind.MONOLITH,
      SplitModeApiRestrictionsService.ModuleKind.MIXED,
      SplitModeApiRestrictionsService.ModuleKind.SHARED,
        -> false
    }
  }

  private class MakeModuleOnlyKindDependenciesFix(
    private val moduleName: String,
    private val desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
  ) : LocalQuickFix {
    override fun getName(): String {
      return message("inspection.remote.dev.make.module.work.in.kind.only.fix.name", moduleName, desiredModuleKind.presentableName)
    }

    override fun getFamilyName(): String {
      return message("inspection.remote.dev.make.only.kind.dependencies.fix.name", desiredModuleKind.presentableName)
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val ideaPlugin = findModuleDescriptor(descriptor) ?: return
      val module = findTargetModule(descriptor)
      val xmlFile = ideaPlugin.xmlElement?.containingFile ?: return
      if (!IntentionPreviewUtils.prepareElementForWrite(xmlFile)) return

      removeInappropriateDependencies(ideaPlugin, module, desiredModuleKind)
      runJpsToBazelConverter(project)
    }
  }

  private class AddExplicitPlatformDependencyFix(
    private val moduleName: String,
    private val desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
  ) : LocalQuickFix {
    override fun getName(): String {
      return message("inspection.remote.dev.make.module.work.in.kind.only.fix.name", moduleName, desiredModuleKind.presentableName)
    }

    override fun getFamilyName(): String {
      return message("inspection.remote.dev.missing.runtime.dependency.fix.add", getExplicitPlatformDependencyName(desiredModuleKind))
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val ideaPlugin = findModuleDescriptor(descriptor) ?: return
      val module = findTargetModule(descriptor)
      val xmlFile = ideaPlugin.xmlElement?.containingFile ?: return
      if (!IntentionPreviewUtils.prepareElementForWrite(xmlFile)) return

      removeInappropriateDependencies(ideaPlugin, module, desiredModuleKind)

      val dependencyName = getExplicitPlatformDependencyName(desiredModuleKind)
      if (!hasDirectDependency(ideaPlugin, dependencyName)) {
        val newModuleEntry = ideaPlugin.dependencies.addModuleEntry()
        newModuleEntry.name.stringValue = dependencyName
      }

      addModuleDependency(module, dependencyName)
      runJpsToBazelConverter(project)
    }
  }
}

private fun removeInappropriateDependencies(
  ideaPlugin: IdeaPlugin,
  module: Module?,
  desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
) {
  for (dependency in ideaPlugin.depends.toList()) {
    val dependencyName = dependency.rawText ?: dependency.stringValue ?: continue
    if (shouldRemoveDependency(dependencyName, desiredModuleKind)) {
      dependency.xmlElement?.delete()
    }
  }

  val dependencies = ideaPlugin.dependencies
  if (!dependencies.isValid) {
    removeInappropriateModuleDependencies(module, desiredModuleKind)
    return
  }

  for (moduleEntry in dependencies.moduleEntry.toList()) {
    val dependencyName = moduleEntry.name.stringValue ?: continue
    if (shouldRemoveDependency(dependencyName, desiredModuleKind)) {
      moduleEntry.xmlElement?.delete()
    }
  }
  for (pluginEntry in dependencies.plugin.toList()) {
    val dependencyName = pluginEntry.id.stringValue ?: continue
    if (shouldRemoveDependency(dependencyName, desiredModuleKind)) {
      pluginEntry.xmlElement?.delete()
    }
  }

  removeInappropriateModuleDependencies(module, desiredModuleKind)
}

private fun findModuleDescriptor(descriptor: ProblemDescriptor): IdeaPlugin? {
  val containingFile = descriptor.psiElement.containingFile
  if (containingFile is XmlFile) {
    val ideaPlugin = DescriptorUtil.getIdeaPlugin(containingFile)
    if (ideaPlugin != null) {
      return ideaPlugin
    }
  }

  val module = ModuleUtilCore.findModuleForPsiElement(descriptor.psiElement) ?: return null
  val descriptorFile = PluginModuleType.getContentModuleDescriptorXml(module) ?: PluginModuleType.getPluginXml(module) ?: return null
  return DescriptorUtil.getIdeaPlugin(descriptorFile)
}

private fun findTargetModule(descriptor: ProblemDescriptor): Module? {
  return ModuleUtilCore.findModuleForPsiElement(descriptor.psiElement)
}

private fun removeInappropriateModuleDependencies(
  module: Module?,
  desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
) {
  if (module == null || IntentionPreviewUtils.isIntentionPreviewActive()) {
    return
  }

  ModuleRootModificationUtil.updateModel(module) { model ->
    for (orderEntry in model.orderEntries) {
      val moduleOrderEntry = orderEntry as? ModuleOrderEntry ?: continue
      if (shouldRemoveDependency(moduleOrderEntry.moduleName, desiredModuleKind)) {
        model.removeOrderEntry(moduleOrderEntry)
      }
    }
  }
}

private fun addModuleDependency(module: Module?, dependencyName: String) {
  if (module == null || IntentionPreviewUtils.isIntentionPreviewActive()) {
    return
  }

  ModuleRootModificationUtil.updateModel(module) { model ->
    if (model.orderEntries.filterIsInstance<ModuleOrderEntry>().any { it.moduleName == dependencyName }) {
      return@updateModel
    }

    val dependencyModule = ModuleManager.getInstance(module.project).findModuleByName(dependencyName)
    if (dependencyModule != null) {
      model.addModuleOrderEntry(dependencyModule)
    }
    else {
      model.addInvalidModuleEntry(dependencyName)
    }
  }
}

private fun runJpsToBazelConverter(project: Project) {
  if (IntentionPreviewUtils.isIntentionPreviewActive() || ApplicationManager.getApplication().isUnitTestMode) {
    return
  }

  val action = ActionManager.getInstance().getAction("MonorepoDevkit.Bazel.JpsToBazelConverter") ?: return
  val event = AnActionEvent.createEvent(action, SimpleDataContext.getProjectContext(project), null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
  ActionUtil.performAction(action, event)
}

private fun shouldRemoveDependency(
  dependencyName: String,
  desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
): Boolean {
  val dependencyKind = SplitModeApiRestrictionsService.getInstance().getDependencyKind(dependencyName)
  return when (desiredModuleKind) {
    SplitModeApiRestrictionsService.ModuleKind.FRONTEND -> {
      dependencyKind == SplitModeApiRestrictionsService.ModuleKind.BACKEND
      || dependencyKind == SplitModeApiRestrictionsService.ModuleKind.MONOLITH
    }
    SplitModeApiRestrictionsService.ModuleKind.BACKEND -> {
      dependencyKind == SplitModeApiRestrictionsService.ModuleKind.FRONTEND
      || dependencyKind == SplitModeApiRestrictionsService.ModuleKind.MONOLITH
    }
    SplitModeApiRestrictionsService.ModuleKind.MONOLITH -> {
      dependencyName == getExplicitPlatformDependencyName(SplitModeApiRestrictionsService.ModuleKind.FRONTEND)
      || dependencyName == getExplicitPlatformDependencyName(SplitModeApiRestrictionsService.ModuleKind.BACKEND)
    }
    SplitModeApiRestrictionsService.ModuleKind.MIXED, SplitModeApiRestrictionsService.ModuleKind.SHARED -> {
      false
    }
  }
}

private fun hasDirectDependency(ideaPlugin: IdeaPlugin, dependencyName: String): Boolean {
  if (ideaPlugin.depends.any { dependency -> dependencyName == (dependency.rawText ?: dependency.stringValue) }) {
    return true
  }

  val dependencies = ideaPlugin.dependencies
  return dependencies.isValid
         && (dependencies.moduleEntry.any { it.name.stringValue == dependencyName }
           || dependencies.plugin.any { it.id.stringValue == dependencyName })
}

internal fun getExplicitPlatformDependencyName(moduleKind: SplitModeApiRestrictionsService.ModuleKind): String {
  return when (moduleKind) {
    SplitModeApiRestrictionsService.ModuleKind.FRONTEND -> FRONTEND_PLATFORM_MODULE_BASE_NAME
    SplitModeApiRestrictionsService.ModuleKind.BACKEND -> BACKEND_PLATFORM_MODULE_BASE_NAME
    SplitModeApiRestrictionsService.ModuleKind.MONOLITH -> MONOLITH_PLATFORM_MODULE_BASE_NAME
    SplitModeApiRestrictionsService.ModuleKind.MIXED, SplitModeApiRestrictionsService.ModuleKind.SHARED -> error("Explicit split-mode dependency is only supported for frontend/backend/monolith module kinds")
  }
}
