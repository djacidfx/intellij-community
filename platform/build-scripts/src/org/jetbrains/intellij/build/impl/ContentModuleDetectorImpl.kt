// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl

import com.intellij.devkit.runtimeModuleRepository.generator.ContentModuleDetector
import com.intellij.devkit.runtimeModuleRepository.generator.ContentModuleRegistrationData
import com.intellij.openapi.util.JDOMUtil
import com.intellij.platform.runtime.repository.RuntimeModuleId
import com.intellij.platform.runtime.repository.RuntimeModuleLoadingRule
import com.intellij.platform.runtime.repository.RuntimeModuleVisibility
import com.intellij.platform.runtime.repository.serialization.RawIncludedRuntimeModule
import com.intellij.platform.runtime.repository.serialization.RawRuntimePluginHeader
import org.jdom.Element
import org.jetbrains.intellij.build.PLUGIN_XML_RELATIVE_PATH
import org.jetbrains.intellij.build.classPath.PluginBuildDescriptor
import org.jetbrains.intellij.build.impl.projectStructureMapping.CustomAssetEntry
import org.jetbrains.intellij.build.impl.projectStructureMapping.ModuleLibraryFileEntry
import org.jetbrains.intellij.build.impl.projectStructureMapping.ModuleOutputEntry
import org.jetbrains.intellij.build.impl.projectStructureMapping.ModuleTestOutputEntry
import org.jetbrains.intellij.build.impl.projectStructureMapping.ProjectLibraryEntry
import org.jetbrains.jps.model.module.JpsModule
import kotlin.io.path.pathString

/**
 * Provide information about [JpsModule] registered as content modules using data [DescriptorCacheContainer].
 */
internal class ContentModuleDetectorImpl(platformLayout: PlatformLayout, bundledPlugins: List<PluginBuildDescriptor>) : ContentModuleDetector {
  private val contentModules = mutableMapOf<String, ContentModuleRegistrationData>()
  private val loadingRulesForContentModules = mutableMapOf<String, RuntimeModuleLoadingRule>()
  private val requiredIfAvailableAttributeForContentModules = mutableMapOf<String, RuntimeModuleId>()
  val pluginHeaders: List<RawRuntimePluginHeader>

  init {
    val platformContainer = platformLayout.descriptorCacheContainer.forPlatform(platformLayout)
    val corePluginContent = platformContainer.getCachedFileData(PRODUCT_DESCRIPTOR_META_PATH) ?: error("Cannot find core plugin descriptor")
    processPluginDescriptor(corePluginContent, platformContainer, presentablePluginDescription = "the core plugin")
    val pluginDescriptorModuleNameToId = HashMap<String, String>()
    bundledPlugins.forEach { plugin ->
      val descriptorContainer = platformLayout.descriptorCacheContainer.forPlugin(plugin.dir)
      val fileContent = descriptorContainer.getCachedFileData(PLUGIN_XML_RELATIVE_PATH)
                        ?: error("Cannot find plugin.xml for ${plugin.dir} in the cache")
      val pluginId = processPluginDescriptor(fileContent, descriptorContainer, presentablePluginDescription = plugin.dir.pathString)
      pluginDescriptorModuleNameToId[plugin.layout.mainModule] = pluginId
    }
    pluginHeaders = bundledPlugins.map { plugin ->
      createPluginHeader(plugin, pluginId = pluginDescriptorModuleNameToId.getValue(plugin.layout.mainModule))
    }
  }

  private fun processPluginDescriptor(
    fileContent: ByteArray,
    descriptorContainer: ScopedCachedDescriptorContainer,
    presentablePluginDescription: String,
  ): String {
    val rootTag = JDOMUtil.load(fileContent)
    val pluginId = rootTag.getChildText("id") ?: error("<id> tag is not set in plugin.xml for $presentablePluginDescription")
    rootTag.getChildren("content").forEach { contentTag ->
      val namespace = contentTag.getAttributeValue("namespace") ?: $$"$${pluginId}_$implicit"
      contentTag.getChildren("module").forEach { moduleTag ->
        val moduleName = moduleTag.getAttributeValue("name") ?: error("'name' attribute is missing for <module> tag in plugin.xml in $presentablePluginDescription")
        if (moduleName.contains("/")) return@forEach //todo remove this check after all content modules are extracted to separate JPS modules (IJPL-165543)

        val moduleXmlData = descriptorContainer.getCachedFileData("$moduleName.xml")
                            ?: error("Cannot find $moduleName.xml descriptor for $presentablePluginDescription")
        val moduleXmlRoot = JDOMUtil.load(moduleXmlData)
        val visibility = parseVisibility(moduleXmlRoot)
        val loadingRule = parseLoadingRule(moduleTag)
        contentModules[moduleName] = ContentModuleRegistrationData(moduleName, namespace, visibility)
        val requiredIfAvailableName = moduleTag.getAttributeValue("required-if-available")
        if (requiredIfAvailableName != null) {
          requiredIfAvailableAttributeForContentModules[moduleName] = RuntimeModuleId.contentModule(requiredIfAvailableName, RuntimeModuleId.DEFAULT_NAMESPACE)
        }
        loadingRulesForContentModules[moduleName] = loadingRule
      }
    }
    return pluginId
  }

  override fun findContentModuleData(jpsModule: JpsModule): ContentModuleRegistrationData? {
    return contentModules[jpsModule.name]
  }

  private fun createPluginHeader(plugin: PluginBuildDescriptor, pluginId: String): RawRuntimePluginHeader {
    val pluginDescriptorModuleId = createModuleId(plugin.layout.mainModule)
    val includedModules = plugin.distribution.mapNotNull { entry ->
      val relativeOutputPath = entry.relativeOutputFile ?: ""
      if (relativeOutputPath.removePrefix("modules/").contains('/')) {
        return@mapNotNull null
      }
      when (entry) {
        is ModuleOutputEntry -> {
          val moduleName = entry.owner.moduleName
          val loadingRule = loadingRulesForContentModules[moduleName] ?: RuntimeModuleLoadingRule.EMBEDDED
          val requiredIfAvailableId = requiredIfAvailableAttributeForContentModules[moduleName]
          RawIncludedRuntimeModule(createModuleId(moduleName), loadingRule, requiredIfAvailableId)
        }
        is ProjectLibraryEntry -> {
          RawIncludedRuntimeModule(RuntimeModuleId.projectLibrary(entry.data.libraryName), RuntimeModuleLoadingRule.EMBEDDED, null)
        }
        is ModuleTestOutputEntry ->
          RawIncludedRuntimeModule(RuntimeModuleId.moduleTests (entry.moduleName), RuntimeModuleLoadingRule.EMBEDDED, null)
        is ModuleLibraryFileEntry -> null // module-level libraries are included in the runtime descriptor for corresponding module
        is CustomAssetEntry -> null
      }
    }
    return RawRuntimePluginHeader.create(pluginId, pluginDescriptorModuleId, includedModules)
  }

  private fun createModuleId(moduleName: String): RuntimeModuleId {
    val pluginDescriptorModuleData = contentModules[moduleName]
    val pluginDescriptorModuleId = if (pluginDescriptorModuleData != null) {
      RuntimeModuleId.contentModule(pluginDescriptorModuleData.name, pluginDescriptorModuleData.namespace)
    }
    else {
      RuntimeModuleId.legacyJpsModule(moduleName)
    }
    return pluginDescriptorModuleId
  }
}

private fun parseVisibility(moduleXmlRoot: Element): RuntimeModuleVisibility {
  val visibilityString = moduleXmlRoot.getAttributeValue("visibility")
  return when (visibilityString) {
    "public" -> RuntimeModuleVisibility.PUBLIC
    "internal" -> RuntimeModuleVisibility.INTERNAL
    "private" -> RuntimeModuleVisibility.PRIVATE
    else -> RuntimeModuleVisibility.PRIVATE
  }
}

private fun parseLoadingRule(moduleTag: Element): RuntimeModuleLoadingRule {
  val loadingRuleString = moduleTag.getAttributeValue("loading")
  return when (loadingRuleString) {
    "required" -> RuntimeModuleLoadingRule.REQUIRED
    "optional" -> RuntimeModuleLoadingRule.OPTIONAL
    "embedded" -> RuntimeModuleLoadingRule.EMBEDDED
    "on-demand" -> RuntimeModuleLoadingRule.ON_DEMAND
    else -> RuntimeModuleLoadingRule.OPTIONAL
  }
}