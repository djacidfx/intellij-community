// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.lang.xml.XMLLanguage
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

internal class ModuleAnalysis(
  val resolvedModuleKind: ResolvedModuleKind,
  val dependencyAnalysis: DependencyAnalysis,
)

internal data class ResolvedModuleKind(
  val kind: SplitModeApiRestrictionsService.ModuleKind,
  val reasoning: String,
)

internal data class DependencyInfo(
  val name: String,
  val originDescription: String,
  val isOwn: Boolean,
)

internal data class ContainingPlugin(
  val xmlFile: XmlFile,
  val module: Module,
  val moduleKind: ResolvedModuleKind,
  val directDependencies: Set<DependencyInfo>,
)

internal class DependencyAnalysis(
  private val moduleName: @NlsSafe String,
  directDependencies: Collection<DependencyInfo>,
  private val containingPlugins: List<ContainingPlugin>,
) {
  private val dependencies: Set<DependencyInfo> = collectDependencies(directDependencies, containingPlugins)
  private val isFrontendModuleByConvention = doesNameConformToConvention(moduleName, getModuleNameVariants("frontend").toList())
  private val isBackendModuleByConvention = doesNameConformToConvention(moduleName, getModuleNameVariants("backend").toList())

  val hasContainingPlugins: Boolean = containingPlugins.isNotEmpty()

  val containingPluginKind: SplitModeApiRestrictionsService.ModuleKind = computeContainingPluginsKind(containingPlugins)

  val declaresExplicitFrontendDependencies: Boolean =
    isFrontendModuleByConvention || dependencies.any { it.isOwn && isExplicitFrontendDependency(it.name) }

  val declaresExplicitBackendDependencies: Boolean =
    isBackendModuleByConvention || dependencies.any { it.isOwn && isExplicitBackendDependency(it.name) }

  val declaresFrontendDependencies: Boolean = dependencies.any { it.isOwn && isFrontendDependency(it.name) }

  val declaresBackendDependencies: Boolean = dependencies.any { it.isOwn && isBackendDependency(it.name) }

  val hasFrontendDependencies: Boolean = dependencies.any { isFrontendDependency(it.name) }

  val hasBackendDependencies: Boolean = dependencies.any { isBackendDependency(it.name) }

  val lacksOwnDependencies: Boolean = !declaresExplicitFrontendDependencies
                                      && !declaresExplicitBackendDependencies
                                      && !declaresFrontendDependencies
                                      && !declaresBackendDependencies

  val hasMixedDependencies: Boolean = (isFrontendModuleByConvention || hasFrontendDependencies)
                                      && (isBackendModuleByConvention || hasBackendDependencies)

  fun buildFrontendReasoning(explicitOnly: Boolean): String {
    return buildReasoning(
      "frontend dependencies",
      isFrontendModuleByConvention,
      explicitOnly,
      ::isFrontendDependency,
      ::isExplicitFrontendDependency,
      false,
    )
  }

  fun buildBackendReasoning(explicitOnly: Boolean): String {
    return buildReasoning(
      "backend dependencies",
      isBackendModuleByConvention,
      explicitOnly,
      ::isBackendDependency,
      ::isExplicitBackendDependency,
      false,
    )
  }

  fun buildMixedReasoning(): String {
    return buildFrontendReasoning(false) + "; " + buildBackendReasoning(false)
  }

  fun buildOwnFrontendReasoning(explicitOnly: Boolean): String {
    return buildReasoning(
      "frontend dependencies",
      isFrontendModuleByConvention,
      explicitOnly,
      ::isFrontendDependency,
      ::isExplicitFrontendDependency,
      requireOwnDependencies = true,
    )
  }

  fun buildOwnBackendReasoning(explicitOnly: Boolean): String {
    return buildReasoning(
      "backend dependencies",
      isBackendModuleByConvention,
      explicitOnly,
      ::isBackendDependency,
      ::isExplicitBackendDependency,
      requireOwnDependencies = true,
    )
  }

  fun buildContainingPluginsReasoning(): String {
    if (containingPlugins.isEmpty()) {
      return "no containing plugin descriptors were found"
    }

    return "module declares no own FE/BE dependencies, but the containing plugin.xml files do: ${
      containingPlugins.sortedBy { it.module.name }.joinToString { containingPlugin ->
        "module '${containingPlugin.module.name}'  -> ${containingPlugin.moduleKind.kind.presentableName}"
      }
    }"
  }

  fun buildSharedReasoning(): String {
    val dependencyNames = dependencies.map { it.name }.distinct()
    if (dependencyNames.isEmpty()) {
      return "no frontend or backend dependencies were found for module '$moduleName'"
    }

    return "no frontend or backend dependencies were found among: ${dependencyNames.joinToString { dependencyName -> "'$dependencyName'" }}"
  }

  fun getFrontendDependencyNames(): String {
    return collectDependencyNames(dependencies, ::isFrontendDependency)
  }

  fun getBackendDependencyNames(): String {
    return collectDependencyNames(dependencies, ::isBackendDependency)
  }

  private fun buildReasoning(
    label: String,
    moduleNameFollowsNamingConvention: Boolean,
    explicitOnly: Boolean,
    dependencyMatcher: (String) -> Boolean,
    explicitDependencyMatcher: (String) -> Boolean,
    requireOwnDependencies: Boolean,
  ): String {
    val relevantDependencies = if (requireOwnDependencies) dependencies.filter { it.isOwn } else dependencies
    val evidence = listOfNotNull(
      if (moduleNameFollowsNamingConvention) "module name '$moduleName'" else null,
    ) + relevantDependencies.filter { explicitDependencyMatcher(it.name) }.map {
      "dependency '${it.name}' from ${it.originDescription}"
    } + if (explicitOnly) {
      emptyList()
    }
                   else {
      relevantDependencies.filter { dependencyMatcher(it.name) }.map { dependency ->
        "dependency '${dependency.name}' from ${dependency.originDescription}"
      }
    }
    return "$label: ${evidence.distinct().joinToString()}"
  }

  private fun collectDependencyNames(
    dependencies: Collection<DependencyInfo>,
    dependencyMatcher: (String) -> Boolean,
  ): String {
    return dependencies.filter { dependencyMatcher(it.name) }.map { it.name }.distinct().joinToString()
  }
}

private fun collectDependencies(
  directDependencies: Collection<DependencyInfo>,
  containingPlugins: List<ContainingPlugin>,
): Set<DependencyInfo> {
  val dependencies = mutableSetOf<DependencyInfo>()
  dependencies.addAll(directDependencies)
  for (containingPlugin in containingPlugins) {
    dependencies.addAll(containingPlugin.directDependencies)
  }
  return dependencies
}

private fun isExplicitFrontendDependency(dependencyName: String): Boolean {
  return dependencyName in getModuleNameVariants(FRONTEND_PLATFORM_MODULE_BASE_NAME).toSet()
}

private fun isExplicitBackendDependency(dependencyName: String): Boolean {
  return dependencyName in getModuleNameVariants(BACKEND_PLATFORM_MODULE_BASE_NAME).toSet()
}

internal fun isFrontendDependency(dependencyName: String): Boolean {
  val dependencyKind = resolveDependencyKind(dependencyName)
  return dependencyKind == SplitModeApiRestrictionsService.ModuleKind.FRONTEND
         || dependencyKind == SplitModeApiRestrictionsService.ModuleKind.MIXED // todo remove
}

internal fun isBackendDependency(dependencyName: String): Boolean {
  val dependencyKind = resolveDependencyKind(dependencyName)
  return dependencyKind == SplitModeApiRestrictionsService.ModuleKind.BACKEND
         || dependencyKind == SplitModeApiRestrictionsService.ModuleKind.MIXED // todo remove
}

private fun computeContainingPluginsKind(containingPlugins: List<ContainingPlugin>): SplitModeApiRestrictionsService.ModuleKind {
  if (containingPlugins.isEmpty()) {
    return SplitModeApiRestrictionsService.ModuleKind.SHARED
  }

  val firstKind = containingPlugins.first().moduleKind.kind
  for (containingPlugin in containingPlugins) {
    if (containingPlugin.moduleKind.kind != firstKind) {
      return SplitModeApiRestrictionsService.ModuleKind.MIXED
    }
  }
  return firstKind
}

internal object SplitModeModuleKindResolver {
  private val LOG = logger<SplitModeModuleKindResolver>()

  fun doesApiKindMatchExpectedModuleKind(
    actualApiUsageModuleKind: ResolvedModuleKind,
    expectedKind: SplitModeApiRestrictionsService.ModuleKind,
  ): Boolean {
    return actualApiUsageModuleKind.kind == SplitModeApiRestrictionsService.ModuleKind.MIXED
           || expectedKind == SplitModeApiRestrictionsService.ModuleKind.SHARED
           || expectedKind == actualApiUsageModuleKind.kind
  }

  fun getOrComputeModuleKind(element: PsiElement): ResolvedModuleKind? {
    val module = ModuleUtilCore.findModuleForPsiElement(element) ?: return null
    return getOrComputeModuleAnalysis(module).resolvedModuleKind
  }

  fun getOrComputeModuleAnalysis(module: Module): ModuleAnalysis {
    return CachedValuesManager.getManager(module.project).getCachedValue(module) {
      CachedValueProvider.Result.create(
        computeModuleAnalysis(module),
        ProjectRootModificationTracker.getInstance(module.project),
        PsiManager.getInstance(module.project).modificationTracker.forLanguage(XMLLanguage.INSTANCE),
      )
    }
  }

  private fun computeModuleAnalysis(module: Module): ModuleAnalysis {
    val moduleName = module.name
    val contentModuleXmlDescriptor = PluginModuleType.getContentModuleDescriptorXml(module)
    val xmlDescriptor = contentModuleXmlDescriptor ?: PluginModuleType.getPluginXml(module)
    val parsedXmlDescriptor = if (xmlDescriptor != null) DescriptorUtil.getIdeaPlugin(xmlDescriptor) else null
    if (xmlDescriptor == null || parsedXmlDescriptor == null) {
      return ModuleAnalysis(
        ResolvedModuleKind(SplitModeApiRestrictionsService.ModuleKind.SHARED, ""),
        DependencyAnalysis(moduleName, emptyList(), emptyList()),
      )
    }

    val directDependencies = collectDescriptorDependencies(moduleName, xmlDescriptor, parsedXmlDescriptor)
    val containingPlugins = if (contentModuleXmlDescriptor == null) emptyList() else collectContainingPlugins(contentModuleXmlDescriptor)
    val dependencyAnalysis = DependencyAnalysis(moduleName, directDependencies, containingPlugins)
    val resolvedModuleKind = computeModuleKind(dependencyAnalysis)

    return ModuleAnalysis(resolvedModuleKind, dependencyAnalysis)
  }

  private fun collectDescriptorDependencies(
    moduleName: String,
    xmlDescriptor: XmlFile,
    ideaPlugin: IdeaPlugin,
  ): Set<DependencyInfo> {
    val descriptorDependencies = mutableSetOf<DependencyInfo>()
    for (dependencyName in SplitModePluginDependencyUtil.collectTransitiveDependencyNames(ideaPlugin)) {
      descriptorDependencies.add(DependencyInfo(dependencyName, "descriptor '${xmlDescriptor.name}' in module '$moduleName'", true))
    }
    return descriptorDependencies
  }

  private fun computeModuleKind(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return when {
      dependencyAnalysis.hasMixedDependencies -> mixedModuleKindFromDependencies(dependencyAnalysis)
      dependencyAnalysis.lacksOwnDependencies
      && dependencyAnalysis.hasContainingPlugins
      && dependencyAnalysis.containingPluginKind == SplitModeApiRestrictionsService.ModuleKind.FRONTEND -> {
        frontendModuleKindFromContainingPlugins(dependencyAnalysis)
      }
      dependencyAnalysis.lacksOwnDependencies
      && dependencyAnalysis.hasContainingPlugins
      && dependencyAnalysis.containingPluginKind == SplitModeApiRestrictionsService.ModuleKind.BACKEND -> {
        backendModuleKindFromContainingPlugins(dependencyAnalysis)
      }
      dependencyAnalysis.lacksOwnDependencies
      && dependencyAnalysis.hasContainingPlugins
      && dependencyAnalysis.containingPluginKind == SplitModeApiRestrictionsService.ModuleKind.MIXED -> {
        mixedModuleKindFromContainingPlugins(dependencyAnalysis)
      }
      dependencyAnalysis.lacksOwnDependencies && dependencyAnalysis.hasContainingPlugins -> {
        sharedModuleKindFromContainingPlugins(dependencyAnalysis)
      }
      dependencyAnalysis.declaresExplicitFrontendDependencies -> frontendModuleKindFromDirectDependencies(dependencyAnalysis)
      dependencyAnalysis.declaresExplicitBackendDependencies -> backendModuleKindFromDirectDependencies(dependencyAnalysis)
      dependencyAnalysis.declaresFrontendDependencies -> frontendModuleKindFromDependencies(dependencyAnalysis)
      dependencyAnalysis.declaresBackendDependencies -> backendModuleKindFromDependencies(dependencyAnalysis)
      else -> sharedModuleKindFromDependencies(dependencyAnalysis)
    }
  }

  private fun mixedModuleKindFromDependencies(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return ResolvedModuleKind(
      SplitModeApiRestrictionsService.ModuleKind.MIXED,
      dependencyAnalysis.buildMixedReasoning(),
    )
  }

  private fun frontendModuleKindFromContainingPlugins(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return ResolvedModuleKind(
      SplitModeApiRestrictionsService.ModuleKind.FRONTEND,
      dependencyAnalysis.buildContainingPluginsReasoning(),
    )
  }

  private fun backendModuleKindFromContainingPlugins(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return ResolvedModuleKind(
      SplitModeApiRestrictionsService.ModuleKind.BACKEND,
      dependencyAnalysis.buildContainingPluginsReasoning(),
    )
  }

  private fun mixedModuleKindFromContainingPlugins(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return ResolvedModuleKind(
      SplitModeApiRestrictionsService.ModuleKind.MIXED,
      dependencyAnalysis.buildContainingPluginsReasoning(),
    )
  }

  private fun sharedModuleKindFromContainingPlugins(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return ResolvedModuleKind(
      SplitModeApiRestrictionsService.ModuleKind.SHARED,
      dependencyAnalysis.buildContainingPluginsReasoning(),
    )
  }

  private fun frontendModuleKindFromDirectDependencies(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return ResolvedModuleKind(
      SplitModeApiRestrictionsService.ModuleKind.FRONTEND,
      dependencyAnalysis.buildOwnFrontendReasoning(true),
    )
  }

  private fun backendModuleKindFromDirectDependencies(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return ResolvedModuleKind(
      SplitModeApiRestrictionsService.ModuleKind.BACKEND,
      dependencyAnalysis.buildOwnBackendReasoning(true),
    )
  }

  private fun frontendModuleKindFromDependencies(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return ResolvedModuleKind(
      SplitModeApiRestrictionsService.ModuleKind.FRONTEND,
      dependencyAnalysis.buildOwnFrontendReasoning(false),
    )
  }

  private fun backendModuleKindFromDependencies(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return ResolvedModuleKind(
      SplitModeApiRestrictionsService.ModuleKind.BACKEND,
      dependencyAnalysis.buildOwnBackendReasoning(false),
    )
  }

  private fun sharedModuleKindFromDependencies(dependencyAnalysis: DependencyAnalysis): ResolvedModuleKind {
    return ResolvedModuleKind(
      SplitModeApiRestrictionsService.ModuleKind.SHARED,
      dependencyAnalysis.buildSharedReasoning(),
    )
  }

  private fun collectContainingPlugins(contentModuleDescriptor: XmlFile): List<ContainingPlugin> {
    val contentModuleName = contentModuleDescriptor.virtualFile?.nameWithoutExtension ?: return emptyList()
    val containingPlugins = ArrayList<ContainingPlugin>()
    val scope = PluginRelatedLocatorsUtils.getCandidatesScope(contentModuleDescriptor.project)

    for (pluginXmlFile in DomService.getInstance().getDomFileCandidates(IdeaPlugin::class.java, scope)) {
      val pluginXml = contentModuleDescriptor.manager.findFile(pluginXmlFile) as? XmlFile ?: continue
      val ideaPlugin = DescriptorUtil.getIdeaPlugin(pluginXml) ?: continue
      if (ideaPlugin.content.none { content -> content.moduleEntry.any { it.name.stringValue == contentModuleName } }) {
        continue
      }

      val containingModule = ModuleUtilCore.findModuleForPsiElement(pluginXml) ?: continue
      val directDependencies = mutableSetOf<DependencyInfo>()
      for (dependencyName in collectDirectDependencyNames(ideaPlugin)) {
        directDependencies.add(
          DependencyInfo(
            dependencyName,
            "containing plugin descriptor '${pluginXml.name}' in module '${containingModule.name}'",
            false,
          )
        )
      }

      containingPlugins.add(
        ContainingPlugin(
          xmlFile = pluginXml,
          module = containingModule,
          moduleKind = getOrComputeModuleAnalysis(containingModule).resolvedModuleKind,
          directDependencies = directDependencies,
        )
      )
    }

    if (containingPlugins.isNotEmpty()) {
      LOG.debug {
        "Content module descriptor ${contentModuleDescriptor.virtualFile.path} is included by plugin descriptors: " +
        containingPlugins.joinToString { containingPlugin ->
          "${containingPlugin.xmlFile.virtualFile.path} [module=${containingPlugin.module.name}, kind=${containingPlugin.moduleKind.kind.presentableName}]"
        }
      }
    }

    return containingPlugins
  }

  private fun collectDirectDependencyNames(ideaPlugin: IdeaPlugin): Set<String> {
    val dependencyNames = mutableSetOf<String>()
    ideaPlugin.depends.mapNotNullTo(dependencyNames) { it.rawText ?: it.stringValue }

    val dependencies = ideaPlugin.dependencies
    if (!dependencies.isValid) {
      return dependencyNames
    }

    dependencies.moduleEntry.mapNotNullTo(dependencyNames) { it.name.stringValue }
    dependencies.plugin.mapNotNullTo(dependencyNames) { it.id.stringValue }
    return dependencyNames
  }
}

private fun resolveDependencyKind(dependencyName: String): SplitModeApiRestrictionsService.ModuleKind? {
  if (isExplicitFrontendDependency(dependencyName)) {
    return SplitModeApiRestrictionsService.ModuleKind.FRONTEND
  }
  if (isExplicitBackendDependency(dependencyName)) {
    return SplitModeApiRestrictionsService.ModuleKind.BACKEND
  }

  return SplitModeApiRestrictionsService.getInstance().getDependencyKind(dependencyName) ?: guessDependencyKindByName(dependencyName)
}

private fun guessDependencyKindByName(dependencyName: String): SplitModeApiRestrictionsService.ModuleKind? {
  return when {
    doesLookLikeFrontendDependency(dependencyName) -> SplitModeApiRestrictionsService.ModuleKind.FRONTEND
    doesLookLikeBackendDependency(dependencyName) -> SplitModeApiRestrictionsService.ModuleKind.BACKEND
    else -> null
  }
}

private fun doesLookLikeFrontendDependency(moduleDependency: String): Boolean {
  return getModuleNameVariants("frontend").any { moduleDependency.endsWith(".$it") }
}

private fun doesLookLikeBackendDependency(moduleDependency: String): Boolean {
  return getModuleNameVariants("backend").any { moduleDependency.endsWith(".$it") }
}

private fun doesNameConformToConvention(moduleName: String, conventionalModuleNameSuffixes: List<String>): Boolean {
  for (suffix in conventionalModuleNameSuffixes) {
    if (moduleName.endsWith(suffix)) {
      return true
    }
  }
  return false
}

private fun getModuleNameVariants(baseName: String): Sequence<String> {
  return sequence {
    yield(baseName)
    yield("$baseName.split")
    yield("$baseName.main")
    yield("$baseName.split.main")
  }
}
