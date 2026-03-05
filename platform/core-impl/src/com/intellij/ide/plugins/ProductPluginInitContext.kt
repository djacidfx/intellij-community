// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins

import com.intellij.ide.plugins.PluginDependencyAnalysis.DependencyRef
import com.intellij.ide.plugins.PluginInitializationContext.EnvironmentConfiguredModuleData
import com.intellij.ide.plugins.PluginManagerCore.JAVA_PLUGIN_ALIAS_ID
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.impl.ApplicationInfoImpl
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.BuildNumber
import com.intellij.util.PlatformUtils
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.VisibleForTesting

@ApiStatus.Internal
class ProductPluginInitContext(
  private val buildNumberOverride: BuildNumber? = null,
  private val disabledPluginsOverride: Set<PluginId>? = null,
  private val expiredPluginsOverride: Set<PluginId>? = null,
  private val brokenPluginVersionsOverride: Map<PluginId, Set<String>>? = null,
) : PluginInitializationContext {
  override val essentialPlugins: Set<PluginId> by lazy {
    buildSet {
      add(PluginManagerCore.CORE_ID)
      addAll(ApplicationInfoImpl.getShadowInstance().getEssentialPluginIds())
    }
  }
  private val disabledPlugins: Set<PluginId> by lazy { disabledPluginsOverride ?: DisabledPluginsState.getDisabledIds() }
  private val expiredPlugins: Set<PluginId> by lazy { expiredPluginsOverride ?: ExpiredPluginsState.expiredPluginIds }
  private val brokenPluginVersions: Map<PluginId, Set<String>> by lazy { brokenPluginVersionsOverride ?: getBrokenPluginVersions() }

  override val productBuildNumber: BuildNumber
    get() = buildNumberOverride ?: PluginManagerCore.buildNumber

  override fun isPluginDisabled(id: PluginId): Boolean {
    return PluginManagerCore.CORE_ID != id && disabledPlugins.contains(id)
  }

  override fun isPluginBroken(id: PluginId, version: String?): Boolean {
    val set = brokenPluginVersions[id] ?: return false
    return set.contains(version)
  }

  override fun isPluginExpired(id: PluginId): Boolean = expiredPlugins.contains(id)

  override val requirePlatformAliasDependencyForLegacyPlugins: Boolean
    get() = !PlatformUtils.isIntelliJ()

  override val checkEssentialPlugins: Boolean
    get() = !PluginManagerCore.isUnitTestMode

  override val explicitPluginSubsetToLoad: Set<PluginId>? by lazy {
    System.getProperty("idea.load.plugins.id")
      ?.splitToSequence(',')
      ?.filter { it.isNotEmpty() }
      ?.map(PluginId::getId)
      ?.toHashSet()
  }

  override val disablePluginLoadingCompletely: Boolean
    get() = !System.getProperty("idea.load.plugins", "true").toBoolean()

  override val pluginsPerProjectConfig: PluginsPerProjectConfig? by lazy {
    if (java.lang.Boolean.getBoolean("ide.per.project.instance")) {
      PluginsPerProjectConfig(
        isMainProcess = !PathManager.getPluginsDir().fileName.toString().startsWith("perProject_")
      )
    }
    else null
  }

  override val currentProductModeId: String
    get() = ProductLoadingStrategy.strategy.currentModeId

  override val environmentConfiguredModules: Map<PluginModuleId, EnvironmentConfiguredModuleData> by lazy {
    buildMap {
      configureProductModeModules(currentProductModeId)
    }
  }

  override fun provideCompatibilityDependencies(descriptor: IdeaPluginDescriptorImpl, pluginSet: UnambiguousPluginSet): Sequence<DependencyRef> =
    defaultProductCompatibilityDependenciesProvider(descriptor, pluginSet)

  companion object {
    @VisibleForTesting
    internal fun MutableMap<PluginModuleId, EnvironmentConfiguredModuleData>.configureProductModeModules(productModeId: String) {
      val frontendSplit = PluginModuleId("intellij.platform.frontend.split", PluginModuleId.JETBRAINS_NAMESPACE)
      val frontend = PluginModuleId("intellij.platform.frontend", PluginModuleId.JETBRAINS_NAMESPACE)
      val backend = PluginModuleId("intellij.platform.backend", PluginModuleId.JETBRAINS_NAMESPACE)
      val backendJps = PluginModuleId("intellij.platform.jps.build", PluginModuleId.JETBRAINS_NAMESPACE)
      val backendJpsGraph = PluginModuleId("intellij.platform.jps.build.dependencyGraph", PluginModuleId.JETBRAINS_NAMESPACE)

      for (moduleId in listOf(frontend, backend, frontendSplit, backendJps, backendJpsGraph)) {
        val isAvailable = when (productModeId) {
          /** intellij.platform.backend.split is currently available in 'monolith' mode because it's used as a backend in CodeWithMe */
          "monolith" -> moduleId != frontendSplit
          "backend" -> moduleId != frontend && moduleId != frontendSplit
          "frontend" -> moduleId != backend && moduleId != backendJps && moduleId != backendJpsGraph
          else -> true
        }
        val unavailabilityReason =
          if (isAvailable) null
          else UnsuitableProductModeModuleUnavailabilityReason(moduleId, productModeId)
        val replaced = put(moduleId, EnvironmentConfiguredModuleData(unavailabilityReason))
        check(replaced == null) { "$moduleId is already registered as environment-configured module" }
      }
    }

    @VisibleForTesting
    fun defaultProductCompatibilityDependenciesProvider(descriptor: IdeaPluginDescriptorImpl, pluginSet: UnambiguousPluginSet): Sequence<DependencyRef> {
      suspend fun SequenceScope<DependencyRef>.yieldIfResolves(ref: DependencyRef) {
        if (pluginSet.resolveReference(ref) != null) {
          yield(ref)
        }
      }
      return sequence {
        // If a plugin does not include any module dependency tags in its plugin.xml, it's assumed to be a legacy plugin
        // and is loaded only in IntelliJ IDEA, so it may use classes from Java plugin.
        if (descriptor is PluginMainDescriptor &&
            pluginSet.resolvePluginId(PluginManagerCore.ALL_MODULES_MARKER) != null &&
            PluginCompatibilityUtils.isLegacyPluginWithoutPlatformAliasDependencies(descriptor)) {
          val java = pluginSet.resolvePluginId(JAVA_PLUGIN_ALIAS_ID)
          if (java != null && java !== descriptor) {
            yield(DependencyRef.of(JAVA_PLUGIN_ALIAS_ID))
            yieldIfResolves(DependencyRef.of(JAVA_BACKEND_MODULE_ID))
          }
        }

        // Check modules as well, for example, intellij.diagram.impl.vcs.
        // We are not yet ready to recommend adding a dependency on extracted VCS modules since the coordinates are not finalized.
        if ((descriptor is PluginMainDescriptor && descriptor.pluginId != PluginManagerCore.CORE_ID) || descriptor is ContentModuleDescriptor) {
          val strictCheck = descriptor.isBundled || PluginManagerCore.isVendorJetBrains(descriptor.vendor ?: "")
          if (!strictCheck || doesDependOnPluginAlias(descriptor, VCS_ALIAS_ID)) {
            vcsApiContentModules.forEach { vcsModule ->
              yieldIfResolves(DependencyRef.of(vcsModule))
            }
          }
          if (!strictCheck) {
            if (System.getProperty("enable.implicit.json.dependency").toBoolean()) {
              yieldIfResolves(DependencyRef.of(JSON_ALIAS_ID))
              yieldIfResolves(DependencyRef.of(JSON_BACKEND_MODULE_ID))
            }
            if (doesDependOnPluginAlias(descriptor, JSON_ALIAS_ID)) {
              yieldIfResolves(DependencyRef.of(JSON_BACKEND_MODULE_ID))
            }
            if (doesDependOnPluginAlias(descriptor, CWM_PLUGIN_ID)) {
              yieldIfResolves(DependencyRef.of(REMOTE_DEVELOPMENT_MODULE_ID))
            }
            if (doesDependOnPluginAlias(descriptor, CWM_RIDER_PLUGIN_ID)) {
              yieldIfResolves(DependencyRef.of(REMOTE_DEVELOPMENT_RIDER_MODULE_ID))
            }
            if (doesDependOnPluginAlias(descriptor, XDEBUGGER_PLUGIN_ALIAS_ID)) {
              for (moduleId in XDEBUGGER_MODULE_IDS) {
                yieldIfResolves(DependencyRef.of(moduleId))
              }
            }
            yieldIfResolves(DependencyRef.of(COLLABORATION_TOOLS_MODULE_ID))
          }

          /* Compatibility Layer */

          if (doesDependOnPluginAlias(descriptor, JAVA_PLUGIN_ALIAS_ID)) {
            yieldIfResolves(DependencyRef.of(JAVA_BACKEND_MODULE_ID))
          }

          if (doesDependOnPluginAlias(descriptor, RIDER_ALIAS_ID)) {
            yieldIfResolves(DependencyRef.of(RIDER_MODULE_ID))
          }
          if (doesDependOnPluginAlias(descriptor, PluginId.getId("org.jetbrains.completion.full.line"))) {
            fullLineApiContentModules.forEach { fullLineModule ->
              yieldIfResolves(DependencyRef.of(fullLineModule))
            }
          }
        }

        for (depends in descriptor.pluginDependencies) {
          if (depends.subDescriptor != null) { // will be processed when invoked for the sub-descriptor
            continue
          }
          if ((depends.pluginId == PLATFORM_PLUGIN_ALIAS_ID || depends.pluginId == LANG_PLUGIN_ALIAS_ID) && pluginSet.resolvePluginId(depends.pluginId) != null) {
            for (contentModuleId in contentModulesExtractedInCorePluginWhichCanBeUsedFromExternalPlugins) {
              yieldIfResolves(DependencyRef.of(contentModuleId))
            }
          }
        }

        if (descriptor is DependsSubDescriptor) {
          if ((descriptor.dependsTargetId == PLATFORM_PLUGIN_ALIAS_ID || descriptor.dependsTargetId == LANG_PLUGIN_ALIAS_ID) && pluginSet.resolvePluginId(descriptor.pluginId) != null) {
            for (contentModuleId in contentModulesExtractedInCorePluginWhichCanBeUsedFromExternalPlugins) {
              yieldIfResolves(DependencyRef.of(contentModuleId))
            }
          }
        }
      }
    }
  }
}

// alias in most cases points to Core plugin, so we cannot use computed dependencies to check
private fun doesDependOnPluginAlias(plugin: IdeaPluginDescriptorImpl, @Suppress("SameParameterValue") aliasId: PluginId): Boolean {
  return plugin.dependencies.any { it.pluginId == aliasId } || plugin.moduleDependencies.plugins.any { it == aliasId }
}

private val JAVA_BACKEND_MODULE_ID = PluginModuleId("intellij.java.backend", PluginModuleId.JETBRAINS_NAMESPACE)
private val VCS_ALIAS_ID = PluginId.getId("com.intellij.modules.vcs")
private val RIDER_ALIAS_ID = PluginId.getId("com.intellij.modules.rider")
private val RIDER_MODULE_ID = PluginModuleId("intellij.rider", PluginModuleId.JETBRAINS_NAMESPACE)
private val JSON_ALIAS_ID = PluginId.getId("com.intellij.modules.json")
private val CWM_PLUGIN_ID = PluginId.getId("com.jetbrains.codeWithMe")
private val CWM_RIDER_PLUGIN_ID = PluginId.getId("intellij.rider.plugins.cwm")
private val JSON_BACKEND_MODULE_ID = PluginModuleId("intellij.json.backend", PluginModuleId.JETBRAINS_NAMESPACE)
private val REMOTE_DEVELOPMENT_MODULE_ID = PluginModuleId("intellij.cwm", PluginModuleId.JETBRAINS_NAMESPACE)
private val REMOTE_DEVELOPMENT_RIDER_MODULE_ID = PluginModuleId("intellij.rider.plugins.cwm", PluginModuleId.JETBRAINS_NAMESPACE)
private val PLATFORM_PLUGIN_ALIAS_ID = PluginId.getId("com.intellij.modules.platform")
private val LANG_PLUGIN_ALIAS_ID = PluginId.getId("com.intellij.modules.lang")
private val XDEBUGGER_PLUGIN_ALIAS_ID = PluginId.getId("com.intellij.modules.xdebugger")
private val XDEBUGGER_MODULE_IDS = listOf(
  PluginModuleId("intellij.platform.debugger", PluginModuleId.JETBRAINS_NAMESPACE),
  PluginModuleId("intellij.platform.debugger.impl", PluginModuleId.JETBRAINS_NAMESPACE),
  PluginModuleId("intellij.platform.debugger.impl.shared", PluginModuleId.JETBRAINS_NAMESPACE),
  PluginModuleId("intellij.platform.debugger.impl.ui", PluginModuleId.JETBRAINS_NAMESPACE),
)

/**
 * List of content modules from the core plugin which should be automatically added as dependencies third-party plugins and plugins with dependency on `com.intellij.modules.vcs`
 * plugin alias for compatibility.
 */
private val vcsApiContentModules = arrayOf(
  "intellij.platform.vcs.impl",
  "intellij.platform.vcs.dvcs",
  "intellij.platform.vcs.dvcs.impl",
  "intellij.platform.vcs.log",
  "intellij.platform.vcs.log.graph",
  "intellij.platform.vcs.log.impl",
).map { PluginModuleId(it, PluginModuleId.JETBRAINS_NAMESPACE) }

private val COLLABORATION_TOOLS_MODULE_ID = PluginModuleId("intellij.platform.collaborationTools", PluginModuleId.JETBRAINS_NAMESPACE)

/**
 * List of content modules from the core plugin which should be automatically added as dependencies to all plugins with dependency on `org.jetbrains.completion.full.line` plugin
 * alias for compatibility.
 */
private val fullLineApiContentModules = arrayOf(
  "intellij.fullLine.core",
  "intellij.fullLine.local",
  "intellij.fullLine.core.impl",
).map { PluginModuleId(it, PluginModuleId.JETBRAINS_NAMESPACE) }

/**
 * Specifies the list of content modules which was recently extracted from the main module of the core plugin and may have external usages.
 * Since such modules were loaded by the core classloader before, it wasn't necessary to specify any dependencies to use classes from them.
 * To avoid breaking compatibility, dependencies on these modules are automatically added to plugins which define dependency on the platform using
 * `<depends>com.intellij.modules.platform</depends>` or `<depends>com.intellij.modules.lang</depends>` tags.
 * See [this article](https://youtrack.jetbrains.com/articles/IJPL-A-956#keep-compatibility-with-external-plugins) for more details.
 */
private val contentModulesExtractedInCorePluginWhichCanBeUsedFromExternalPlugins = arrayOf(
  "intellij.platform.collaborationTools.auth",
  "intellij.platform.collaborationTools.auth.base",
  "intellij.platform.tasks",
  "intellij.platform.tasks.impl",
  "intellij.platform.scriptDebugger.ui",
  "intellij.platform.scriptDebugger.backend",
  "intellij.platform.scriptDebugger.protocolReaderRuntime",
  "intellij.spellchecker.xml",
  "intellij.relaxng",
  "intellij.spellchecker",
  "intellij.platform.structuralSearch",
  "intellij.xml.emmet",
).map { PluginModuleId(it, PluginModuleId.JETBRAINS_NAMESPACE) }