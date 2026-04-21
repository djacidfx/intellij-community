// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins

import com.intellij.icons.AllIcons
import com.intellij.ide.IdeBundle
import com.intellij.ide.plugins.PluginManagerConfigurablePanel.applyUpdates
import com.intellij.ide.plugins.PluginManagerConfigurablePanel.clearUpdates
import com.intellij.ide.plugins.PluginManagerConfigurablePanel.createScrollPane
import com.intellij.ide.plugins.PluginManagerConfigurablePanel.registerCopyProvider
import com.intellij.ide.plugins.PluginManagerConfigurablePanel.setState
import com.intellij.ide.plugins.PluginManagerConfigurablePanel.showRightBottomPopup
import com.intellij.ide.plugins.marketplace.statistics.PluginManagerUsageCollector
import com.intellij.ide.plugins.newui.ListPluginComponent
import com.intellij.ide.plugins.newui.MultiSelectionEventHandler
import com.intellij.ide.plugins.newui.MyPluginModel
import com.intellij.ide.plugins.newui.PluginDetailsPageComponent
import com.intellij.ide.plugins.newui.PluginModelFacade
import com.intellij.ide.plugins.newui.PluginUiModel
import com.intellij.ide.plugins.newui.PluginUpdatesService
import com.intellij.ide.plugins.newui.PluginsGroup
import com.intellij.ide.plugins.newui.PluginsGroupComponent
import com.intellij.ide.plugins.newui.PluginsGroupComponentWithProgress
import com.intellij.ide.plugins.newui.PluginsTab
import com.intellij.ide.plugins.newui.SearchQueryParser
import com.intellij.ide.plugins.newui.SearchResultPanel
import com.intellij.ide.plugins.newui.SearchUpDownPopupController
import com.intellij.ide.plugins.newui.SearchWords
import com.intellij.ide.plugins.newui.UIPluginGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeepPopupOnPerform
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.labels.LinkListener
import com.intellij.util.containers.ContainerUtil
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import java.util.SortedSet
import java.util.function.Consumer
import java.util.function.Supplier
import javax.swing.JComponent
import javax.swing.JLabel

@ApiStatus.Internal
class InstalledPluginsTab(
  private val myPluginModelFacade: PluginModelFacade,
  private val myPluginUpdatesService: PluginUpdatesService,
  private val myCoroutineScope: CoroutineScope,
  private val mySearchInMarketplaceTabHandler: Consumer<String>?,
) : PluginsTab() {
  private var myInstalledPanel: PluginsGroupComponentWithProgress? = null
  private var myInstalledSearchPanel: InstalledPluginsTabSearchResultPanel? = null
  private val myInstalledSearchGroup = DefaultActionGroup()

  private val myBundledUpdateGroup =
    PluginsGroup(IdeBundle.message("plugins.configurable.bundled.updates"), PluginsGroupType.BUNDLED_UPDATE)

  private val myUpdateAll: LinkLabel<Any?> =
    PluginManagerConfigurablePanel.LinkLabelButton(IdeBundle.message("plugin.manager.update.all"), null)
  private val myUpdateAllBundled: LinkLabel<Any?> =
    PluginManagerConfigurablePanel.LinkLabelButton(IdeBundle.message("plugin.manager.update.all"), null)
  private val myUpdateCounter: JLabel = CountComponent()
  private val myUpdateCounterBundled: JLabel = CountComponent()

  init {
    for (option in InstalledSearchOption.entries) {
      myInstalledSearchGroup.add(InstalledSearchOptionAction(option))
    }

    myUpdateAll.isVisible = false
    myUpdateAllBundled.isVisible = false
    myUpdateCounter.isVisible = false
    myUpdateCounterBundled.isVisible = false
  }

  fun getInstalledPanel(): PluginsGroupComponentWithProgress? {
    return myInstalledPanel
  }

  fun getInstalledSearchPanel(): SearchResultPanel? {
    return myInstalledSearchPanel
  }

  fun getInstalledGroups(): List<UIPluginGroup>? {
    return if (getInstalledPanel() != null) getInstalledPanel()!!.groups else null
  }

  override fun createDetailsPanel(searchListener: LinkListener<Any>): PluginDetailsPageComponent {
    val detailPanel = PluginDetailsPageComponent(myPluginModelFacade, searchListener, false)
    myPluginModelFacade.getModel().addDetailPanel(detailPanel)
    return detailPanel
  }

  override fun createPluginsPanel(selectionListener: Consumer<in PluginsGroupComponent?>): JComponent {
    val eventHandler = MultiSelectionEventHandler()
    val installedPanel = object : PluginsGroupComponentWithProgress(eventHandler) {
      override fun createListComponent(
        model: PluginUiModel,
        group: PluginsGroup,
        listPluginModel: ListPluginModel,
      ): ListPluginComponent {
        return ListPluginComponent(myPluginModelFacade, model, group, listPluginModel, searchListener, myCoroutineScope, false)
      }
    }
    myInstalledPanel = installedPanel

    installedPanel.setSelectionListener(selectionListener)
    installedPanel.accessibleContext.accessibleName = IdeBundle.message("plugin.manager.installed.panel.accessible.name")
    registerCopyProvider(installedPanel)

    //noinspection ConstantConditions
    (myInstalledSearchPanel!!.controller as SearchUpDownPopupController).setEventHandler(eventHandler)
    installedPanel.showLoadingIcon()

    val userInstalled = PluginsGroup(IdeBundle.message("plugins.configurable.userInstalled"), PluginsGroupType.INSTALLED)
    val installing = PluginsGroup(IdeBundle.message("plugins.configurable.installing"), PluginsGroupType.INSTALLING)

    val updateAllListener = LinkListener<Any?> { _, _ ->
      myUpdateAll.isEnabled = false
      myUpdateAllBundled.isEnabled = false

      for (group in getInstalledGroups()!!) {
        if (group.isBundledUpdatesGroup) {
          continue
        }
        for (plugin in group.plugins) {
          plugin.updatePlugin()
        }
      }
    }

    myUpdateAll.setListener(updateAllListener, null)
    userInstalled.addSecondaryAction(myUpdateAll)
    userInstalled.addSecondaryAction(myUpdateCounter)

    myUpdateAllBundled.setListener(updateAllListener, null)
    myBundledUpdateGroup.addSecondaryAction(myUpdateAllBundled)
    myBundledUpdateGroup.addSecondaryAction(myUpdateCounterBundled)

    PluginManagerPanelFactory.createInstalledPanel(myCoroutineScope, myPluginModelFacade.getModel()) { model ->
      try {
        myPluginModelFacade.getModel().setDownloadedGroup(installedPanel, userInstalled, installing)
        installing.preloadedModel.setErrors(model.errors)
        installing.preloadedModel.setPluginInstallationStates(model.installationStates)
        installing.addModels(MyPluginModel.installingPlugins)
        if (!installing.models.isEmpty()) {
          installing.sortByName()
          installing.titleWithCount()
          installedPanel.addGroup(installing)
        }

        userInstalled.preloadedModel.setErrors(model.errors)
        userInstalled.preloadedModel.setPluginInstallationStates(model.installationStates)
        userInstalled.addModels(model.installedPlugins)

        myBundledUpdateGroup.preloadedModel.setErrors(model.errors)
        myBundledUpdateGroup.preloadedModel.setPluginInstallationStates(model.installationStates)

        // bundled includes bundled plugin updates
        val visibleNonBundledPlugins = ArrayList<PluginUiModel>()
        val visibleBundledPlugins = ArrayList<PluginUiModel>()
        for (plugin in model.visiblePlugins) {
          if (plugin.isBundled || plugin.isBundledUpdate) {
            visibleBundledPlugins.add(plugin)
          }
          else {
            visibleNonBundledPlugins.add(plugin)
          }
        }

        val installedPluginIds = model.installedPlugins.map { it.pluginId }

        val nonBundledPlugins = ArrayList<PluginUiModel>()
        for (plugin in visibleNonBundledPlugins) {
          if (!installedPluginIds.contains(plugin.pluginId)) {
            nonBundledPlugins.add(plugin)
          }
        }

        userInstalled.addModels(nonBundledPlugins)

        val defaultCategory = IdeBundle.message("plugins.configurable.other.bundled")

        val promotionPanelSuppliers = HashMap<String, Supplier<JComponent?>>()
        if (Registry.`is`("ide.plugins.category.promotion.enabled")) {
          for (provider in PROMOTION_EP_NAME.extensionList) {
            promotionPanelSuppliers[provider.getCategoryName()] = Supplier { provider.createPromotionPanel() }
          }
        }

        val groupedVisibleBundledPlugins = HashMap<String, MutableList<PluginUiModel>>()
        for (descriptor in visibleBundledPlugins) {
          val category = StringUtil.defaultIfEmpty(descriptor.displayCategory, defaultCategory)
          val group = groupedVisibleBundledPlugins.getOrPut(category) { ArrayList() }
          group.add(descriptor)
        }

        val sortedBundledGroups = ArrayList<ComparablePluginsGroup>()
        for ((category, descriptors) in groupedVisibleBundledPlugins) {
          val group = ComparablePluginsGroup(category, descriptors, model.visiblePluginsRequiresUltimate)
          if (Registry.`is`("ide.plugins.category.promotion.enabled")) {
            val promotionPanelSupplier = promotionPanelSuppliers[category]
            if (promotionPanelSupplier != null) {
              val promotionPanel = promotionPanelSupplier.get()
              if (promotionPanel != null) {
                group.setPromotionPanel(promotionPanel)
              }
            }
          }
          sortedBundledGroups.add(group)
        }
        sortedBundledGroups.sortWith { o1, o2 ->
          if (Registry.`is`("ide.plugins.category.promotion.enabled")) {
            var isPriorityO1 = false
            var isPriorityO2 = false
            for (provider in PROMOTION_EP_NAME.extensionList) {
              if (provider.isPriorityCategory()) {
                val priorityCategory = provider.getCategoryName()
                if (priorityCategory == o1.title) {
                  isPriorityO1 = true
                }
                if (priorityCategory == o2.title) {
                  isPriorityO2 = true
                }
              }
            }
            if (isPriorityO1 != isPriorityO2) {
              return@sortWith if (isPriorityO1) -1 else 1
            }
          }
          if (defaultCategory == o1.title) {
            return@sortWith 1
          }
          if (defaultCategory == o2.title) {
            return@sortWith -1
          }
          o1.compareTo(o2)
        }

        if (Registry.`is`("ide.plugins.category.promotion.enabled")) {
          // Add priority groups with promotion panel before userInstalled
          for (group in sortedBundledGroups) {
            if (group.promotionPanel != null) {
              group.preloadedModel.setErrors(model.errors)
              group.preloadedModel.setPluginInstallationStates(model.installationStates)
              installedPanel.addGroup(group)
              myPluginModelFacade.getModel().addEnabledGroup(group)
            }
          }
        }

        if (!userInstalled.models.isEmpty()) {
          userInstalled.sortByName()

          var enabledNonBundledCount = 0L
          for (descriptor in nonBundledPlugins) {
            if (!myPluginModelFacade.getModel().isDisabled(descriptor.pluginId)) {
              enabledNonBundledCount++
            }
          }
          userInstalled.titleWithCount(Math.toIntExact(enabledNonBundledCount))
          if (userInstalled.ui == null) {
            installedPanel.addGroup(userInstalled)
          }
          myPluginModelFacade.getModel().addEnabledGroup(userInstalled)
        }

        for (group in sortedBundledGroups) {
          if (!Registry.`is`("ide.plugins.category.promotion.enabled") || group.promotionPanel == null) {
            group.preloadedModel.setErrors(model.errors)
            group.preloadedModel.setPluginInstallationStates(model.installationStates)
            installedPanel.addGroup(group)
            myPluginModelFacade.getModel().addEnabledGroup(group)
          }
        }

        myPluginUpdatesService.calculateUpdates { updates ->
          val updateModels = updates?.filter { plugin -> myPluginModelFacade.isEnabled(plugin) }
                             ?: emptyList()
          if (ContainerUtil.isEmpty(updateModels)) {
            clearUpdates(installedPanel)
            clearUpdates(myInstalledSearchPanel!!.panel)
          }
          else {
            applyUpdates(installedPanel, updateModels)
            applyUpdates(myInstalledSearchPanel!!.panel, updateModels)
          }
          applyBundledUpdates(updateModels)
          selectionListener.accept(installedPanel)
          selectionListener.accept(myInstalledSearchPanel!!.panel)
        }
      }
      finally {
        installedPanel.hideLoadingIcon()
      }
    }

    return createScrollPane(installedPanel, true)
  }

  override fun createSearchTextField(flyDelay: Int) {
    super.createSearchTextField(flyDelay)

    val textField = searchTextField.textEditor

    @Suppress("DialogTitleCapitalization")
    val searchOptionsText = IdeBundle.message("plugins.configurable.search.options")
    val searchFieldExtension = ExtendableTextComponent.Extension.create(
      /* defaultIcon = */ AllIcons.General.Filter,
      /* hoveredIcon = */ AllIcons.General.Filter,
      /* tooltip = */ searchOptionsText,
      /* focusable = */ true,
    ) {
      showRightBottomPopup(textField, IdeBundle.message("plugins.configurable.show"), myInstalledSearchGroup)
    }
    textField.putClientProperty("search.extension", searchFieldExtension)
    textField.putClientProperty("JTextField.variant", null)
    textField.putClientProperty("JTextField.variant", "search")

    searchTextField.setHistoryPropertyName("InstalledPluginsSearchHistory")
  }

  override fun createSearchPanel(selectionListener: Consumer<in PluginsGroupComponent?>): SearchResultPanel {
    val installedController = object : SearchUpDownPopupController(searchTextField) {
      override fun getAttributes(): List<String> {
        return listOf(
          "/userInstalled",
          "/outdated",
          "/enabled",
          "/disabled",
          "/invalid",
          "/bundled",
          "/updatedBundled",
          SearchWords.VENDOR.value,
          SearchWords.TAG.value,
        )
      }

      override fun getValues(attribute: String): SortedSet<String>? {
        @Suppress("UNCHECKED_CAST")
        return if (SearchWords.VENDOR.value == attribute) {
          myPluginModelFacade.getModel().vendors as SortedSet<String>?
        }
        else if (SearchWords.TAG.value == attribute) {
          myPluginModelFacade.getModel().tags as SortedSet<String>?
        }
        else {
          null
        }
      }

      override fun showPopupForQuery() {
        showSearchPanel(searchTextField.text)
      }
    }

    val eventHandler = MultiSelectionEventHandler()
    installedController.setSearchResultEventHandler(eventHandler)

    val panel = object : PluginsGroupComponentWithProgress(eventHandler) {
      override fun createListComponent(
        model: PluginUiModel,
        group: PluginsGroup,
        listPluginModel: ListPluginModel,
      ): ListPluginComponent {
        return ListPluginComponent(myPluginModelFacade, model, group, listPluginModel, searchListener, myCoroutineScope, false)
      }
    }

    panel.setSelectionListener(selectionListener)
    registerCopyProvider(panel)

    val installedSearchPanel = InstalledPluginsTabSearchResultPanel(
      myCoroutineScope,
      installedController,
      panel,
      myInstalledSearchGroup,
      Supplier { myInstalledPanel },
      selectionListener,
      if (mySearchInMarketplaceTabHandler == null) null else Consumer<String?> { query -> mySearchInMarketplaceTabHandler.accept(query!!) },
      myPluginModelFacade,
    )
    myInstalledSearchPanel = installedSearchPanel
    return installedSearchPanel
  }

  override fun updateMainSelection(selectionListener: Consumer<in PluginsGroupComponent?>) {
    selectionListener.accept(myInstalledPanel)
  }

  override fun hideSearchPanel() {
    super.hideSearchPanel()
    myPluginModelFacade.getModel().setInvalidFixCallback(null)
  }

  override fun onSearchReset() {
    PluginManagerUsageCollector.searchReset()
  }

  private fun handleSearchOptionSelection(updateAction: InstalledSearchOptionAction) {
    val queries = ArrayList<String>()
    object : SearchQueryParser.Installed(searchTextField.text) {
      override fun addToSearchQuery(query: String) {
        queries.add(query)
      }

      override fun handleAttribute(name: String, value: String) {
        if (!updateAction.myIsSelected) {
          queries.add(name + if (value.isEmpty()) "" else wrapAttribute(value))
        }
      }
    }

    if (updateAction.myIsSelected) {
      queries.add(updateAction.query)
    }
    else {
      queries.remove(updateAction.query)
    }

    val query = StringUtil.join(queries, " ")
    searchTextField.setTextIgnoreEvents(query)
    if (query.isEmpty()) {
      hideSearchPanel()
    }
    else {
      showSearchPanel(query)
    }
  }

  private fun applyBundledUpdates(updates: Collection<PluginUiModel>?) {
    if (updates.isNullOrEmpty()) {
      if (myBundledUpdateGroup.ui != null) {
        getInstalledPanel()!!.removeGroup(myBundledUpdateGroup)
        getInstalledPanel()!!.doLayout()
      }
    }
    else if (myBundledUpdateGroup.ui == null) {
      val secondaryActions = myBundledUpdateGroup.secondaryActions
      if (secondaryActions.isNullOrEmpty()) {
        // removeGroup clears actions too
        myBundledUpdateGroup.addSecondaryAction(myUpdateAllBundled)
        myBundledUpdateGroup.addSecondaryAction(myUpdateCounterBundled)
      }
      for (descriptor in updates) {
        for (group in getInstalledPanel()!!.groups) {
          val component = group.findComponent(descriptor.pluginId)
          if (component != null && component.pluginModel.isBundled) {
            myBundledUpdateGroup.addModel(component.pluginModel)
            break
          }
        }
      }
      if (!myBundledUpdateGroup.models.isEmpty()) {
        var insertPosition = 0
        if (Registry.`is`("ide.plugins.category.promotion.enabled")) {
          val groups = getInstalledPanel()!!.groups
          for (i in groups.indices) {
            if (groups[i].promotionPanel != null) {
              insertPosition = i + 1
              break
            }
          }
        }
        getInstalledPanel()!!.addGroup(myBundledUpdateGroup, insertPosition)
        myBundledUpdateGroup.ui!!.isBundledUpdatesGroup = true

        for (descriptor in updates) {
          val component = myBundledUpdateGroup.ui!!.findComponent(descriptor.pluginId)
          component?.setUpdateDescriptor(descriptor)
        }

        getInstalledPanel()!!.doLayout()
      }
    }
    else {
      val toDelete = ArrayList<ListPluginComponent>()

      for (plugin in myBundledUpdateGroup.ui!!.plugins) {
        var exist = false
        for (update in updates) {
          if (plugin.pluginModel.pluginId == update.pluginId) {
            exist = true
            break
          }
        }
        if (!exist) {
          toDelete.add(plugin)
        }
      }

      for (component in toDelete) {
        getInstalledPanel()!!.removeFromGroup(myBundledUpdateGroup, component.pluginModel)
      }

      for (update in updates) {
        val exist = myBundledUpdateGroup.ui!!.findComponent(update.pluginId)
        if (exist != null) {
          continue
        }
        for (group in getInstalledPanel()!!.groups) {
          if (group == myBundledUpdateGroup.ui) {
            continue
          }
          val component = group.findComponent(update.pluginId)
          if (component != null && component.pluginModel.isBundled) {
            getInstalledPanel()!!.addToGroup(myBundledUpdateGroup, component.pluginModel)
            break
          }
        }
      }

      if (myBundledUpdateGroup.models.isEmpty()) {
        getInstalledPanel()!!.removeGroup(myBundledUpdateGroup)
      }
      else {
        for (descriptor in updates) {
          val component = myBundledUpdateGroup.ui!!.findComponent(descriptor.pluginId)
          component?.setUpdateDescriptor(descriptor)
        }
      }

      getInstalledPanel()!!.doLayout()
    }

    myUpdateAll.isVisible = myUpdateAll.isVisible && myBundledUpdateGroup.ui == null
    myUpdateCounter.isVisible = myUpdateCounter.isVisible && myBundledUpdateGroup.ui == null
  }

  fun onPluginUpdatesRecalculation(updatesCount: Int?, tooltip: @Nls String?) {
    val count = updatesCount ?: 0
    val text = count.toString()
    val visible = count > 0

    myUpdateAll.isEnabled = true
    myUpdateAllBundled.isEnabled = true
    myUpdateAll.isVisible = visible && myBundledUpdateGroup.ui == null
    myUpdateAllBundled.isVisible = visible

    myUpdateCounter.text = text
    myUpdateCounter.toolTipText = tooltip
    myUpdateCounterBundled.text = text
    myUpdateCounterBundled.toolTipText = tooltip
    myUpdateCounter.isVisible = visible && myBundledUpdateGroup.ui == null
    myUpdateCounterBundled.isVisible = visible
  }

  @ApiStatus.Internal
  internal inner class InstalledSearchOptionAction(private val myOption: InstalledSearchOption)
    : ToggleAction(myOption.myPresentableNameSupplier), DumbAware {
    var myIsSelected: Boolean = false

    init {
      templatePresentation.setKeepPopupOnPerform(KeepPopupOnPerform.IfRequested)
    }

    override fun isSelected(e: AnActionEvent): Boolean {
      return myIsSelected
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      myIsSelected = state
      handleSearchOptionSelection(this)
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
      return ActionUpdateThread.BGT
    }

    fun setState(parser: SearchQueryParser.Installed?) {
      if (parser == null) {
        myIsSelected = false
        return
      }

      myIsSelected = when (myOption) {
        InstalledSearchOption.Enabled -> parser.enabled
        InstalledSearchOption.Disabled -> parser.disabled
        InstalledSearchOption.UserInstalled -> parser.userInstalled
        InstalledSearchOption.Bundled -> parser.bundled
        InstalledSearchOption.UpdatedBundled -> parser.updatedBundled
        InstalledSearchOption.Invalid -> parser.invalid
        InstalledSearchOption.NeedUpdate -> parser.needUpdate
      }
    }

    val query: String
      get() {
        return if (myOption == InstalledSearchOption.NeedUpdate) "/outdated" else "/" + StringUtil.decapitalize(myOption.name)
      }
  }

  private inner class ComparablePluginsGroup(
    category: @NlsSafe String,
    descriptors: List<PluginUiModel>,
    private val myPluginsRequiresUltimateButItsDisabled: Map<PluginId, Boolean>,
  ) : PluginsGroup(category, PluginsGroupType.INSTALLED), Comparable<ComparablePluginsGroup> {
    private var myIsEnable = false

    init {
      addModels(descriptors)
      sortByName()

      mainAction = PluginManagerConfigurablePanel.LinkLabelButton("", null) { _, _ -> setEnabledState() }
      val hasPluginsAvailableForEnableDisable =
        descriptors.any { !myPluginsRequiresUltimateButItsDisabled[it.pluginId]!! }
      mainAction!!.isVisible = hasPluginsAvailableForEnableDisable
      titleWithEnabled(myPluginModelFacade)
    }

    override fun titleWithEnabled(pluginModelFacade: PluginModelFacade) {
      var enabled = 0
      for (descriptor in models) {
        if (pluginModelFacade.isLoaded(descriptor) &&
            pluginModelFacade.isEnabled(descriptor) &&
            !myPluginsRequiresUltimateButItsDisabled.getOrDefault(descriptor.pluginId, false) &&
            !descriptor.isIncompatible
        ) {
          enabled++
        }
      }
      titleWithCount(enabled)
    }

    override fun compareTo(other: ComparablePluginsGroup): Int {
      return StringUtil.compare(title, other.title, true)
    }

    override fun titleWithCount(enabled: Int) {
      myIsEnable = enabled == 0
      val key = if (myIsEnable) "plugins.configurable.enable.all" else "plugins.configurable.disable.all"
      mainAction!!.text = IdeBundle.message(key)
    }

    private fun setEnabledState() {
      setState(myPluginModelFacade, models, myIsEnable)
    }
  }

  internal enum class InstalledSearchOption(val myPresentableNameSupplier: Supplier<String>) {
    UserInstalled(IdeBundle.messagePointer("plugins.configurable.InstalledSearchOption.UserInstalled")),
    NeedUpdate(IdeBundle.messagePointer("plugins.configurable.InstalledSearchOption.NeedUpdate")),
    Enabled(IdeBundle.messagePointer("plugins.configurable.InstalledSearchOption.Enabled")),
    Disabled(IdeBundle.messagePointer("plugins.configurable.InstalledSearchOption.Disabled")),
    Invalid(IdeBundle.messagePointer("plugins.configurable.InstalledSearchOption.Invalid")),
    Bundled(IdeBundle.messagePointer("plugins.configurable.InstalledSearchOption.Bundled")),
    UpdatedBundled(IdeBundle.messagePointer("plugins.configurable.InstalledSearchOption.UpdatedBundled")),
  }

  private companion object {
    val PROMOTION_EP_NAME: ExtensionPointName<PluginCategoryPromotionProvider> =
      ExtensionPointName.create("com.intellij.pluginCategoryPromotionProvider")
  }
}
