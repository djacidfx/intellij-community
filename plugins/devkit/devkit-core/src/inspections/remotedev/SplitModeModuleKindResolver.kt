// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.util.NlsSafe
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.xml.XmlFile
import com.intellij.util.xml.DomService
import org.jetbrains.idea.devkit.dom.IdeaPlugin
import org.jetbrains.idea.devkit.module.PluginModuleType
import org.jetbrains.idea.devkit.util.DescriptorUtil
import org.jetbrains.idea.devkit.util.PluginRelatedLocatorsUtils

private const val FRONTEND_PLATFORM_MODULE_BASE_NAME = "intellij.platform.frontend"
private const val BACKEND_PLATFORM_MODULE_BASE_NAME = "intellij.platform.backend"

internal object SplitModeModuleKindResolver {
  private val LOG: Logger = logger<SplitModeModuleKindResolver>()

  fun getOrComputeModuleKind(element: PsiElement): SplitModeApiRestrictionsService.ModuleKind {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return SplitModeApiRestrictionsService.ModuleKind.SHARED
    return getOrComputeModuleKind(module)
  }

  private fun getOrComputeModuleKind(module: Module): SplitModeApiRestrictionsService.ModuleKind {
    val project = module.project
    return CachedValuesManager.getManager(project).getCachedValue(module) {
      val moduleKind = computeModuleKind(module)
      CachedValueProvider.Result.create(
        moduleKind,
        ProjectRootModificationTracker.getInstance(project),
        PsiManager.getInstance(project).modificationTracker.forLanguage(XMLLanguage.INSTANCE),
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

  internal fun analyzeModuleDependencies(
    moduleName: @NlsSafe String,
    allModuleDependencies: Set<String>,
  ): AnalyzedModuleDependencies {
    val frontendDependencies = mutableSetOf<String>()
    val backendDependencies = mutableSetOf<String>()

    allModuleDependencies.forEach { dependencyName ->
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

    return AnalyzedModuleDependencies(moduleName, allModuleDependencies, frontendDependencies, backendDependencies)
  }

  private fun computeModuleKind(module: Module): SplitModeApiRestrictionsService.ModuleKind {
    val moduleName = module.name
    val explicitModuleKind = when {
      getModuleNameVariants("frontend",
                            includeSplit = true,
                            includeGradle = true).any { moduleName.endsWith(it) } -> SplitModeApiRestrictionsService.ModuleKind.FRONTEND
      getModuleNameVariants("backend",
                            includeSplit = true,
                            includeGradle = true).any { moduleName.endsWith(it) } -> SplitModeApiRestrictionsService.ModuleKind.BACKEND
      else -> null
    }

    val contentModuleXmlDescriptor = PluginModuleType.getContentModuleDescriptorXml(module)
    val pluginXmlDescriptor = if (contentModuleXmlDescriptor != null) null else PluginModuleType.getPluginXml(module)

    val effectiveXmlModuleDescriptor = contentModuleXmlDescriptor ?: pluginXmlDescriptor

    if (effectiveXmlModuleDescriptor == null) {
      return explicitModuleKind ?: SplitModeApiRestrictionsService.ModuleKind.SHARED
    }

    val parsedXmlDescriptor = DescriptorUtil.getIdeaPlugin(effectiveXmlModuleDescriptor)
    if (parsedXmlDescriptor == null) {
      return explicitModuleKind ?: SplitModeApiRestrictionsService.ModuleKind.SHARED
    }

    val containingPluginsInfo = contentModuleXmlDescriptor?.let { collectContainingPluginsInfo(it) }
    val allDependencies = LinkedHashSet(SplitModePluginDependencyUtil.collectTransitiveDependencyNames(parsedXmlDescriptor))
    allDependencies.addAll(containingPluginsInfo?.directDependencyNames.orEmpty())
    val moduleDependencyAnalysis = analyzeModuleDependencies(moduleName, allDependencies)
    val containingPluginModuleKind = containingPluginsInfo?.effectiveModuleKind

    return when {
      moduleDependencyAnalysis.hasMixedDependencies -> SplitModeApiRestrictionsService.ModuleKind.MIXED
      moduleDependencyAnalysis.hasNoMarkerDependencies && containingPluginModuleKind != null -> containingPluginModuleKind
      moduleDependencyAnalysis.hasExplicitFrontendDependencies -> SplitModeApiRestrictionsService.ModuleKind.FRONTEND
      moduleDependencyAnalysis.hasExplicitBackendDependencies -> SplitModeApiRestrictionsService.ModuleKind.BACKEND
      moduleDependencyAnalysis.hasEffectivelyFrontendDependencies -> SplitModeApiRestrictionsService.ModuleKind.LIKELY_FRONTEND
      moduleDependencyAnalysis.hasEffectivelyBackendDependencies -> SplitModeApiRestrictionsService.ModuleKind.LIKELY_BACKEND
      explicitModuleKind != null -> explicitModuleKind
      else -> SplitModeApiRestrictionsService.ModuleKind.SHARED
    }
  }

  private fun collectContainingPluginsInfo(contentModuleDescriptor: XmlFile): ContainingPluginsInfo {
    val contentModuleName = contentModuleDescriptor.virtualFile?.nameWithoutExtension
                            ?: return ContainingPluginsInfo(emptySet(), null)
    val containingPlugins = mutableListOf<ContainingPluginInfo>()
    val scope = PluginRelatedLocatorsUtils.getCandidatesScope(contentModuleDescriptor.project)
    DomService.getInstance().getDomFileCandidates(IdeaPlugin::class.java, scope).forEach { pluginXmlFile ->
      val pluginXml = contentModuleDescriptor.manager.findFile(pluginXmlFile) as? XmlFile ?: return@forEach
      val ideaPlugin = DescriptorUtil.getIdeaPlugin(pluginXml) ?: return@forEach
      if (ideaPlugin.content.none { content -> content.moduleEntry.any { it.name.stringValue == contentModuleName } }) {
        return@forEach
      }
      val containingModule = ModuleUtilCore.findModuleForPsiElement(pluginXml) ?: return@forEach
      containingPlugins += ContainingPluginInfo(
        xmlFile = pluginXml,
        moduleName = containingModule.name,
        moduleKind = getOrComputeModuleKind(containingModule),
        directDependencyNames = collectDirectDependencyNames(ideaPlugin),
      )
    }

    if (containingPlugins.isNotEmpty()) {
      LOG.debug {
        "Content module descriptor ${contentModuleDescriptor.virtualFile.path} is included by plugin descriptors: " +
        containingPlugins.joinToString {
          "${it.xmlFile.virtualFile.path} [module=${it.moduleName}, kind=${it.moduleKind.presentableName}]"
        }
      }
    }

    val distinctKinds = containingPlugins.map { it.moduleKind }.distinct()
    val effectiveModuleKind = when {
      distinctKinds.isEmpty() -> null
      distinctKinds.size == 1 -> distinctKinds.single()
      else -> SplitModeApiRestrictionsService.ModuleKind.MIXED
    }
    return ContainingPluginsInfo(
      directDependencyNames = containingPlugins.flatMapTo(LinkedHashSet()) { it.directDependencyNames },
      effectiveModuleKind = effectiveModuleKind,
    )
  }

  private fun collectDirectDependencyNames(ideaPlugin: IdeaPlugin): Set<String> {
    val dependencyNames = LinkedHashSet<String>()
    ideaPlugin.depends.mapNotNullTo(dependencyNames) { it.rawText ?: it.stringValue }

    val dependencies = ideaPlugin.dependencies
    if (!dependencies.isValid) {
      return dependencyNames
    }

    dependencies.moduleEntry.mapNotNullTo(dependencyNames) { it.name.stringValue }
    dependencies.plugin.mapNotNullTo(dependencyNames) { it.id.stringValue }
    return dependencyNames
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

  internal data class AnalyzedModuleDependencies(
    val moduleName: @NlsSafe String,
    val allDependencies: Set<String>,
    val frontendDependencies: Set<String>,
    val backendDependencies: Set<String>,
  ) {
    val hasExplicitFrontendDependencies: Boolean = isDefinitelyFrontendModule(moduleName, allDependencies)
    val hasExplicitBackendDependencies: Boolean = isDefinitelyBackendModule(moduleName, allDependencies)
    val hasEffectivelyFrontendDependencies: Boolean = frontendDependencies.isNotEmpty()
    val hasEffectivelyBackendDependencies: Boolean = backendDependencies.isNotEmpty()

    val hasNoMarkerDependencies: Boolean =
      !hasExplicitFrontendDependencies
      && !hasExplicitBackendDependencies
      && !hasEffectivelyFrontendDependencies
      && !hasEffectivelyBackendDependencies

    val hasMixedDependencies: Boolean =
      (hasEffectivelyFrontendDependencies || hasExplicitFrontendDependencies)
      && (hasEffectivelyBackendDependencies || hasExplicitBackendDependencies)
  }

  private data class ContainingPluginsInfo(
    val directDependencyNames: Set<String>,
    val effectiveModuleKind: SplitModeApiRestrictionsService.ModuleKind?,
  )

  private data class ContainingPluginInfo(
    val xmlFile: XmlFile,
    val moduleName: String,
    val moduleKind: SplitModeApiRestrictionsService.ModuleKind,
    val directDependencyNames: Set<String>,
  )
}
