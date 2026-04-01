// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.jetbrains.idea.devkit.dom.IdeaPlugin
import org.jetbrains.idea.devkit.module.PluginModuleType
import org.jetbrains.idea.devkit.util.DescriptorUtil

private const val FRONTEND_PLATFORM_MODULE_BASE_NAME = "intellij.platform.frontend"
private const val BACKEND_PLATFORM_MODULE_BASE_NAME = "intellij.platform.backend"

private val likelyFrontendDependencies = setOf<String>()
private val likelyBackendDependencies = setOf("intellij.platform.vcs.impl")

internal object FrontendBackendModuleKindResolver {
  fun getOrComputeModuleType(element: PsiElement): ApiRestrictionsService.ModuleKind {
    val cacheHolder = element.containingFile ?: return ApiRestrictionsService.ModuleKind.SHARED
    return CachedValuesManager.getCachedValue(cacheHolder) {
      val moduleType = computeModuleType(cacheHolder)
      CachedValueProvider.Result.create(moduleType, PsiModificationTracker.MODIFICATION_COUNT)
    }
  }

  private fun computeModuleType(file: PsiFile): ApiRestrictionsService.ModuleKind {
    val module = ModuleUtilCore.findModuleForFile(file) ?: return ApiRestrictionsService.ModuleKind.SHARED

    val moduleName = module.name
    when {
      moduleName.endsWith("frontend") -> return ApiRestrictionsService.ModuleKind.FRONTEND
      moduleName.endsWith("backend") -> return ApiRestrictionsService.ModuleKind.BACKEND
    }

    val pluginXml = PluginModuleType.getPluginXml(module)
                    ?: PluginModuleType.getContentModuleDescriptorXml(module)
                    ?: return ApiRestrictionsService.ModuleKind.SHARED
    val ideaPlugin = DescriptorUtil.getIdeaPlugin(pluginXml) ?: return ApiRestrictionsService.ModuleKind.SHARED

    val allDependencies = collectAllDirectDependencies(ideaPlugin)

    return when {
      isDefinitelyFrontendModule(moduleName, allDependencies) -> ApiRestrictionsService.ModuleKind.FRONTEND
      isDefinitelyBackendModule(moduleName, allDependencies) -> ApiRestrictionsService.ModuleKind.BACKEND
      doesLookLikeFrontendDependencies(allDependencies) -> ApiRestrictionsService.ModuleKind.LIKELY_FRONTEND
      doesLookLikeBackendDependencies(allDependencies) -> ApiRestrictionsService.ModuleKind.LIKELY_BACKEND
      else -> ApiRestrictionsService.ModuleKind.SHARED
    }
  }

  private fun isDefinitelyFrontendModule(moduleName: String, moduleDependencies: Set<String>): Boolean {
    return getModuleNameVariants("frontend", includeSplit = true, includeGradle = true).any { moduleName.endsWith(".$it") }
           || getModuleNameVariants(FRONTEND_PLATFORM_MODULE_BASE_NAME).any { it in moduleDependencies }
  }

  private fun isDefinitelyBackendModule(moduleName: String, moduleDependencies: Set<String>): Boolean {
    return getModuleNameVariants("backend", includeSplit = true, includeGradle = true).any { moduleName.endsWith(".$it") }
           || getModuleNameVariants(BACKEND_PLATFORM_MODULE_BASE_NAME).any { it in moduleDependencies }
  }

  private fun doesLookLikeFrontendDependencies(moduleDependencies: Set<String>): Boolean {
    return moduleDependencies.any { moduleDependency ->
      moduleDependency in likelyFrontendDependencies
      || getModuleNameVariants("frontend").any { moduleDependency.endsWith(".$it") }
    }
  }

  private fun doesLookLikeBackendDependencies(moduleDependencies: Set<String>): Boolean {
    return moduleDependencies.any { moduleDependency ->
      moduleDependency in likelyBackendDependencies
      || getModuleNameVariants("backend").any { moduleDependency.endsWith(".$it") }
    }
  }

  private fun collectAllDirectDependencies(ideaPlugin: IdeaPlugin): Set<String> {
    val allDependencies = mutableSetOf<String>()

    ideaPlugin.getDepends().forEach { dependency ->
      dependency.rawText?.let { allDependencies.add(it) }
    }

    val dependenciesDescriptor = ideaPlugin.dependencies
    if (dependenciesDescriptor.isValid) {
      dependenciesDescriptor.moduleEntry.forEach { moduleDescriptor ->
        moduleDescriptor.name.stringValue?.let { allDependencies.add(it) }
      }
      dependenciesDescriptor.plugin.forEach { pluginDescriptor ->
        pluginDescriptor.id.stringValue?.let { allDependencies.add(it) }
      }
    }
    return allDependencies
  }

  private fun getModuleNameVariants(
    baseName: String,
    includeSplit: Boolean = true,
    includeGradle: Boolean = true,
  ): Sequence<String> {
    return sequence {
      yield(baseName)
      if (includeSplit) yield("$baseName.split")
      if (includeGradle) yield("$baseName.main")
      if (includeSplit && includeGradle) yield("$baseName.split.main")
    }
  }
}
