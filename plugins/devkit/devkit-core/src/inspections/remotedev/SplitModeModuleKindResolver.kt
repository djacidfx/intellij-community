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

private val likelyFrontendDependencies = setOf(
  "intellij.platform.frontend.split",
  "intellij.rd.client",
  "com.intellij.jetbrains.client",
  "intellij.cwm.plugin.frontend"
)
private val likelyBackendDependencies = setOf(
  "intellij.platform.vcs.impl",
  "intellij.platform.backend.split",
  "com.jetbrains.remoteDevelopment",
  "intellij.cwm.plugin",
)

internal object SplitModeModuleKindResolver {
  fun getOrComputeModuleKind(element: PsiElement): ApiRestrictionsService.ModuleKind {
    val cacheHolder = element.containingFile ?: return ApiRestrictionsService.ModuleKind.SHARED
    return CachedValuesManager.getCachedValue(cacheHolder) {
      val moduleKind = computeModuleKind(cacheHolder)
      CachedValueProvider.Result.create(moduleKind, PsiModificationTracker.MODIFICATION_COUNT)
    }
  }

  fun doesApiKindMatchExpectedModuleKind(actualApiUsageModuleKind: ApiRestrictionsService.ModuleKind, expectedKind: ApiRestrictionsService.ModuleKind): Boolean {
    return when {
      expectedKind == ApiRestrictionsService.ModuleKind.SHARED -> true
      actualApiUsageModuleKind == ApiRestrictionsService.ModuleKind.SHARED -> false
      else -> expectedKind.id == actualApiUsageModuleKind.id
    }
  }

  internal fun collectMatchedDependencies(
    dependencyNames: Iterable<String>,
    dependencyKindResolver: (String) -> ApiRestrictionsService.ModuleKind?,
  ): MatchedDependencies {
    val frontendDependencies = linkedSetOf<String>()
    val backendDependencies = linkedSetOf<String>()

    dependencyNames.forEach { dependencyName ->
      when (dependencyKindResolver(dependencyName)) {
        ApiRestrictionsService.ModuleKind.FRONTEND,
        ApiRestrictionsService.ModuleKind.LIKELY_FRONTEND,
          -> frontendDependencies.add(dependencyName)
        ApiRestrictionsService.ModuleKind.BACKEND,
        ApiRestrictionsService.ModuleKind.LIKELY_BACKEND,
          -> backendDependencies.add(dependencyName)
        ApiRestrictionsService.ModuleKind.MIXED -> {
          frontendDependencies.add(dependencyName)
          backendDependencies.add(dependencyName)
        }
        ApiRestrictionsService.ModuleKind.SHARED, null -> Unit
      }
    }

    return MatchedDependencies(frontendDependencies, backendDependencies)
  }

  private fun computeModuleKind(file: PsiFile): ApiRestrictionsService.ModuleKind {
    val module = ModuleUtilCore.findModuleForFile(file) ?: return ApiRestrictionsService.ModuleKind.SHARED

    val moduleName = module.name
    val explicitModuleKind = when {
      moduleName.endsWith("frontend") -> ApiRestrictionsService.ModuleKind.FRONTEND
      moduleName.endsWith("backend") -> ApiRestrictionsService.ModuleKind.BACKEND
      else -> null
    }

    val pluginXml = PluginModuleType.getPluginXml(module) ?: PluginModuleType.getContentModuleDescriptorXml(module)
    if (pluginXml == null) {
      return explicitModuleKind ?: ApiRestrictionsService.ModuleKind.SHARED
    }

    val ideaPlugin = DescriptorUtil.getIdeaPlugin(pluginXml)
    if (ideaPlugin == null) {
      return explicitModuleKind ?: ApiRestrictionsService.ModuleKind.SHARED
    }

    val allDependencies = collectAllDirectDependencies(ideaPlugin)
    val matchedDependencies = collectMatchedDependencies(allDependencies, ::resolveDependencyKind)

    return when {
      matchedDependencies.isMixed -> ApiRestrictionsService.ModuleKind.MIXED
      explicitModuleKind != null -> explicitModuleKind
      isDefinitelyFrontendModule(moduleName, allDependencies) -> ApiRestrictionsService.ModuleKind.FRONTEND
      isDefinitelyBackendModule(moduleName, allDependencies) -> ApiRestrictionsService.ModuleKind.BACKEND
      matchedDependencies.hasFrontend -> ApiRestrictionsService.ModuleKind.LIKELY_FRONTEND
      matchedDependencies.hasBackend -> ApiRestrictionsService.ModuleKind.LIKELY_BACKEND
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

  private fun resolveDependencyKind(dependencyName: String): ApiRestrictionsService.ModuleKind? {
    return ApiRestrictionsService.getInstance().getDependencyKind(dependencyName)
           ?: when {
             doesLookLikeFrontendDependency(dependencyName) -> ApiRestrictionsService.ModuleKind.LIKELY_FRONTEND
             doesLookLikeBackendDependency(dependencyName) -> ApiRestrictionsService.ModuleKind.LIKELY_BACKEND
             else -> null
           }
  }

  private fun doesLookLikeFrontendDependency(moduleDependency: String): Boolean {
    return moduleDependency in likelyFrontendDependencies
           || getModuleNameVariants("frontend").any { moduleDependency.endsWith(".$it") }
  }

  private fun doesLookLikeBackendDependency(moduleDependency: String): Boolean {
    return moduleDependency in likelyBackendDependencies
           || getModuleNameVariants("backend").any { moduleDependency.endsWith(".$it") }
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
