// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlFile
import org.jetbrains.idea.devkit.DevKitBundle.message
import org.jetbrains.idea.devkit.dom.IdeaPlugin
import org.jetbrains.idea.devkit.module.PluginModuleType
import org.jetbrains.idea.devkit.util.DescriptorUtil

internal object SplitModeDependencyQuickFixes {
  fun createMismatchFixes(
    moduleAnalysis: ModuleAnalysis,
    desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
  ): Array<LocalQuickFix> {
    if (desiredModuleKind != SplitModeApiRestrictionsService.ModuleKind.FRONTEND
        && desiredModuleKind != SplitModeApiRestrictionsService.ModuleKind.BACKEND) {
      return emptyArray()
    }

    val fixes = ArrayList<LocalQuickFix>()
    if (shouldOfferMakeOnlyDependenciesFix(moduleAnalysis.dependencyAnalysis, desiredModuleKind)) {
      fixes.add(MakeModuleOnlyKindDependenciesFix(desiredModuleKind))
    }
    if (shouldOfferAddExplicitDependencyFix(moduleAnalysis.dependencyAnalysis, desiredModuleKind)) {
      fixes.add(AddExplicitPlatformDependencyFix(desiredModuleKind))
    }
    return fixes.toTypedArray()
  }

  fun createMixedModuleFixes(dependencyAnalysis: DependencyAnalysis): Array<LocalQuickFix> {
    val fixes = ArrayList<LocalQuickFix>()
    if (dependencyAnalysis.declaresBackendDependencies) {
      fixes.add(MakeModuleOnlyKindDependenciesFix(SplitModeApiRestrictionsService.ModuleKind.FRONTEND))
    }
    if (dependencyAnalysis.declaresFrontendDependencies) {
      fixes.add(MakeModuleOnlyKindDependenciesFix(SplitModeApiRestrictionsService.ModuleKind.BACKEND))
    }
    return fixes.toTypedArray()
  }

  fun createAddExplicitDependencyFix(moduleKind: SplitModeApiRestrictionsService.ModuleKind): LocalQuickFix {
    return AddExplicitPlatformDependencyFix(moduleKind)
  }

  private fun shouldOfferMakeOnlyDependenciesFix(
    dependencyAnalysis: DependencyAnalysis,
    desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
  ): Boolean {
    return when (desiredModuleKind) {
      SplitModeApiRestrictionsService.ModuleKind.FRONTEND -> dependencyAnalysis.declaresBackendDependencies
      SplitModeApiRestrictionsService.ModuleKind.BACKEND -> dependencyAnalysis.declaresFrontendDependencies
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
      SplitModeApiRestrictionsService.ModuleKind.MIXED,
      SplitModeApiRestrictionsService.ModuleKind.SHARED,
      -> false
    }
  }

  private class MakeModuleOnlyKindDependenciesFix(private val desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind) : LocalQuickFix {
    override fun getFamilyName(): String {
      return message("inspection.remote.dev.make.only.kind.dependencies.fix.name", desiredModuleKind.presentableName)
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val ideaPlugin = findModuleDescriptor(descriptor) ?: return
      val xmlFile = ideaPlugin.xmlElement?.containingFile ?: return
      if (!IntentionPreviewUtils.prepareElementForWrite(xmlFile)) return

      for (dependency in ideaPlugin.depends.toList()) {
        val dependencyName = dependency.rawText ?: dependency.stringValue ?: continue
        if (shouldRemoveDependency(dependencyName, desiredModuleKind)) {
          dependency.xmlElement?.delete()
        }
      }

      val dependencies = ideaPlugin.dependencies
      if (!dependencies.isValid) {
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
    }
  }

  private class AddExplicitPlatformDependencyFix(private val desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind) : LocalQuickFix {
    override fun getFamilyName(): String {
      return message("inspection.remote.dev.missing.runtime.dependency.fix.add", getExplicitDependencyName(desiredModuleKind))
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val ideaPlugin = findModuleDescriptor(descriptor) ?: return
      val xmlFile = ideaPlugin.xmlElement?.containingFile ?: return
      if (!IntentionPreviewUtils.prepareElementForWrite(xmlFile)) return

      val dependencyName = getExplicitDependencyName(desiredModuleKind)
      if (hasDirectDependency(ideaPlugin, dependencyName)) {
        return
      }

      val newModuleEntry = ideaPlugin.dependencies.addModuleEntry()
      newModuleEntry.name.stringValue = dependencyName
    }
  }
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

private fun shouldRemoveDependency(
  dependencyName: String,
  desiredModuleKind: SplitModeApiRestrictionsService.ModuleKind,
): Boolean {
  return when (desiredModuleKind) {
    SplitModeApiRestrictionsService.ModuleKind.FRONTEND -> isBackendDependency(dependencyName)
    SplitModeApiRestrictionsService.ModuleKind.BACKEND -> isFrontendDependency(dependencyName)
    SplitModeApiRestrictionsService.ModuleKind.MIXED,
    SplitModeApiRestrictionsService.ModuleKind.SHARED,
    -> false
  }
}

private fun hasDirectDependency(ideaPlugin: IdeaPlugin, dependencyName: String): Boolean {
  if (ideaPlugin.depends.any { dependency -> dependencyName == (dependency.rawText ?: dependency.stringValue) }) {
    return true
  }

  val dependencies = ideaPlugin.dependencies
  return dependencies.isValid && (
    dependencies.moduleEntry.any { it.name.stringValue == dependencyName }
    || dependencies.plugin.any { it.id.stringValue == dependencyName }
  )
}

private fun getExplicitDependencyName(moduleKind: SplitModeApiRestrictionsService.ModuleKind): String {
  return when (moduleKind) {
    SplitModeApiRestrictionsService.ModuleKind.FRONTEND -> "intellij.platform.frontend"
    SplitModeApiRestrictionsService.ModuleKind.BACKEND -> "intellij.platform.backend"
    SplitModeApiRestrictionsService.ModuleKind.MIXED,
    SplitModeApiRestrictionsService.ModuleKind.SHARED,
    -> error("Explicit split-mode dependency is only supported for frontend/backend module kinds")
  }
}
