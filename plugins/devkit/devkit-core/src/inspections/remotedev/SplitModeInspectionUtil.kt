// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.IntelliJProjectUtil
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.annotations.Nls
import org.jetbrains.idea.devkit.DevKitBundle
import org.jetbrains.idea.devkit.module.PluginModuleType
import org.jetbrains.idea.devkit.util.DescriptorUtil

internal object SplitModeInspectionUtil {
  @Nls
  fun buildMixedModuleDependenciesMessage(dependencyAnalysis: DependencyAnalysis): String {
    return DevKitBundle.message("inspection.remote.dev.mixed.dependencies.message") + DevKitBundle.message(
      "inspection.api.usage.restricted.to.module.type.reasoning.message.suffix",
      dependencyAnalysis.buildMixedReasoning(),
    )
  }

  @Nls
  fun buildModuleKindMismatchMessage(
    apiName: @NlsSafe String,
    expectedModuleKind: SplitModeApiRestrictionsService.ModuleKind,
    actualModuleKind: ResolvedModuleKind,
  ): String {
    val baseMessage = DevKitBundle.message(
      "inspection.api.usage.restricted.to.module.type.default.message",
      apiName,
      expectedModuleKind.presentableName,
      actualModuleKind.kind.presentableName,
    )
    return if (actualModuleKind.reasoning.isBlank()) {
      baseMessage
    }
    else {
      baseMessage + DevKitBundle.message(
        "inspection.api.usage.restricted.to.module.type.reasoning.message.suffix",
        actualModuleKind.reasoning,
      )
    }
  }

  fun shouldSuppressForSingleModuleExternalPlugin(file: PsiFile): Boolean {
    val module = ModuleUtilCore.findModuleForPsiElement(file) ?: return false
    val project = file.project
    return CachedValuesManager.getManager(project).getCachedValue(module) {
      CachedValueProvider.Result.create(
        shouldSuppressForSingleModuleExternalPlugin(module),
        ProjectRootModificationTracker.getInstance(project),
        PsiManager.getInstance(project).modificationTracker.forLanguage(XMLLanguage.INSTANCE),
      )
    }
  }

  private fun shouldSuppressForSingleModuleExternalPlugin(module: Module): Boolean {
    if (IntelliJProjectUtil.isIntelliJPlatformProject(module.project)) {
      return false
    }

    val pluginXml = PluginModuleType.getPluginXml(module) ?: return false
    if (PluginModuleType.getContentModuleDescriptorXml(module) != null) {
      return false
    }

    val ideaPlugin = DescriptorUtil.getIdeaPlugin(pluginXml) ?: return false
    return ideaPlugin.content.isEmpty() || ideaPlugin.content.all { it.moduleEntry.isEmpty() }
  }
}
