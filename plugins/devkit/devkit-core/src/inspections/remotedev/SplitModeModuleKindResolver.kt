// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.idea.devkit.module.PluginModuleType
import org.jetbrains.idea.devkit.util.DescriptorUtil

private const val FRONTEND_PLATFORM_MODULE_BASE_NAME = "intellij.platform.frontend"
private const val BACKEND_PLATFORM_MODULE_BASE_NAME = "intellij.platform.backend"

internal object SplitModeModuleKindResolver {
  fun getOrComputeModuleKind(element: PsiElement): SplitModeApiRestrictionsService.ModuleKind {
    val cacheHolder = element.containingFile ?: return SplitModeApiRestrictionsService.ModuleKind.SHARED
    return CachedValuesManager.getCachedValue(cacheHolder) {
      val moduleKind = computeModuleKind(cacheHolder)
      CachedValueProvider.Result.create(
        moduleKind,
        ProjectRootModificationTracker.getInstance(cacheHolder.project),
        cacheHolder.manager.modificationTracker.forLanguage(XMLLanguage.INSTANCE),
      )
    }
  }

  fun doesApiKindMatchExpectedModuleKind(
    actualApiUsageModuleKind: SplitModeApiRestrictionsService.ModuleKind,
    expectedKind: SplitModeApiRestrictionsService.ModuleKind,
  ): Boolean {
    return when {
      expectedKind.id == SplitModeApiRestrictionsService.ModuleKind.SHARED.id -> true
      else -> expectedKind.id == actualApiUsageModuleKind.id
    }
  }

  internal fun collectMatchedDependencies(
    dependencyNames: Iterable<String>,
  ): MatchedDependencies {
    val frontendDependencies = mutableSetOf<String>()
    val backendDependencies = mutableSetOf<String>()

    dependencyNames.forEach { dependencyName ->
      when (resolveDependencyKind(dependencyName)) {
        SplitModeApiRestrictionsService.ModuleKind.FRONTEND, SplitModeApiRestrictionsService.ModuleKind.LIKELY_FRONTEND -> {
          frontendDependencies.add(dependencyName)
        }
        SplitModeApiRestrictionsService.ModuleKind.BACKEND, SplitModeApiRestrictionsService.ModuleKind.LIKELY_BACKEND -> {
          backendDependencies.add(dependencyName)
        }
        SplitModeApiRestrictionsService.ModuleKind.MIXED -> {
          frontendDependencies.add(dependencyName)
          backendDependencies.add(dependencyName)
        }
        SplitModeApiRestrictionsService.ModuleKind.SHARED, null -> {}
      }
    }

    return MatchedDependencies(frontendDependencies, backendDependencies)
  }

  private fun computeModuleKind(file: PsiFile): SplitModeApiRestrictionsService.ModuleKind {
    val module = ModuleUtilCore.findModuleForFile(file) ?: return SplitModeApiRestrictionsService.ModuleKind.SHARED

    val moduleName = module.name
    val explicitModuleKind = when {
      getModuleNameVariants("frontend", includeSplit = true, includeGradle = true).any { moduleName.endsWith(it) } -> SplitModeApiRestrictionsService.ModuleKind.FRONTEND
      getModuleNameVariants("backend", includeSplit = true, includeGradle = true).any { moduleName.endsWith(it) } -> SplitModeApiRestrictionsService.ModuleKind.BACKEND
      else -> null
    }

    val pluginXml = PluginModuleType.getPluginXml(module) ?: PluginModuleType.getContentModuleDescriptorXml(module)
    if (pluginXml == null) {
      return explicitModuleKind ?: SplitModeApiRestrictionsService.ModuleKind.SHARED
    }

    val ideaPlugin = DescriptorUtil.getIdeaPlugin(pluginXml)
    if (ideaPlugin == null) {
      return explicitModuleKind ?: SplitModeApiRestrictionsService.ModuleKind.SHARED
    }

    val allDependencies = SplitModePluginDependencyUtil.collectTransitiveDependencyNames(ideaPlugin)
    val matchedDependencies = collectMatchedDependencies(allDependencies)

    return when {
      matchedDependencies.isMixed -> SplitModeApiRestrictionsService.ModuleKind.MIXED
      explicitModuleKind != null -> explicitModuleKind
      isDefinitelyFrontendModule(moduleName, allDependencies) -> SplitModeApiRestrictionsService.ModuleKind.FRONTEND
      isDefinitelyBackendModule(moduleName, allDependencies) -> SplitModeApiRestrictionsService.ModuleKind.BACKEND
      matchedDependencies.hasFrontend -> SplitModeApiRestrictionsService.ModuleKind.LIKELY_FRONTEND
      matchedDependencies.hasBackend -> SplitModeApiRestrictionsService.ModuleKind.LIKELY_BACKEND
      else -> SplitModeApiRestrictionsService.ModuleKind.SHARED
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

  private fun resolveDependencyKind(dependencyName: String): SplitModeApiRestrictionsService.ModuleKind? {
    return SplitModeApiRestrictionsService.getInstance().getDependencyKind(dependencyName)
           ?: guessModuleKind(dependencyName)
  }

  private fun guessModuleKind(dependencyName: String): SplitModeApiRestrictionsService.ModuleKind? = when {
    doesLookLikeFrontendDependency(dependencyName) -> SplitModeApiRestrictionsService.ModuleKind.LIKELY_FRONTEND
    doesLookLikeBackendDependency(dependencyName) -> SplitModeApiRestrictionsService.ModuleKind.LIKELY_BACKEND
    else -> null
  }

  private fun doesLookLikeFrontendDependency(moduleDependency: String): Boolean {
    return getModuleNameVariants("frontend").any { moduleDependency.endsWith(".$it") }
  }

  private fun doesLookLikeBackendDependency(moduleDependency: String): Boolean {
    return getModuleNameVariants("backend").any { moduleDependency.endsWith(".$it") }
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

  internal data class MatchedDependencies(
    val frontendDependencies: Set<String>,
    val backendDependencies: Set<String>,
  ) {
    val hasFrontend: Boolean
      get() = frontendDependencies.isNotEmpty()

    val hasBackend: Boolean
      get() = backendDependencies.isNotEmpty()

    val isMixed: Boolean
      get() = hasFrontend && hasBackend
  }
}
