// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins

import com.intellij.ide.plugins.marketplace.PluginSearchResult
import com.intellij.ide.plugins.newui.PluginInstallationState
import com.intellij.ide.plugins.newui.PluginUiModel
import com.intellij.ide.plugins.newui.PluginsViewCustomizer
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.util.IntellijInternalApi
import com.intellij.openapi.util.text.HtmlChunk
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
@IntellijInternalApi
object PluginManagerPanelFactory {
  private val LOG = Logger.getInstance(PluginManagerPanelFactory::class.java)

}

@Service
@ApiStatus.Internal
class PluginManagerCoroutineScopeHolder(val coroutineScope: CoroutineScope)

@ApiStatus.Internal
@IntellijInternalApi
data class CreateInstalledPanelModel(
  val installedPlugins: List<PluginUiModel>,
  val visiblePlugins: List<PluginUiModel>,
  val errors: Map<PluginId, List<HtmlChunk>>,
  val visiblePluginsRequiresUltimate: Map<PluginId, Boolean>,
  val installationStates: Map<PluginId, PluginInstallationState>,
)

@ApiStatus.Internal
@IntellijInternalApi
data class CreateMarketplacePanelModel(
  val marketplaceData: Map<String, PluginSearchResult>,
  val errors: Map<PluginId, List<HtmlChunk>>,
  val suggestedPlugins: List<PluginUiModel>,
  val customRepositories: Map<String, List<PluginUiModel>>,
  val installedPlugins: Map<PluginId, PluginUiModel>,
  val installationStates: Map<PluginId, PluginInstallationState>,
  val internalPluginsGroupDescriptor: PluginsViewCustomizer.PluginsGroupDescriptor?,
)