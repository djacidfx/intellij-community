@file:Suppress("removal", "DEPRECATION", "UsePropertyAccessSyntax", "ReplaceJavaStaticMethodWithKotlinAnalog", "CascadeIf")

// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins

import com.intellij.ide.CopyProvider
import com.intellij.ide.DataManager
import com.intellij.ide.IdeBundle
import com.intellij.ide.plugins.certificates.PluginCertificateManager
import com.intellij.ide.plugins.marketplace.statistics.PluginManagerUsageCollector
import com.intellij.ide.plugins.newui.ListPluginComponent
import com.intellij.ide.plugins.newui.MyPluginModel
import com.intellij.ide.plugins.newui.PluginManagerCustomizer
import com.intellij.ide.plugins.newui.PluginModelAsyncOperationsExecutor
import com.intellij.ide.plugins.newui.PluginModelFacade
import com.intellij.ide.plugins.newui.PluginPriceService
import com.intellij.ide.plugins.newui.PluginUiModel
import com.intellij.ide.plugins.newui.PluginUpdatesService
import com.intellij.ide.plugins.newui.PluginsGroup
import com.intellij.ide.plugins.newui.PluginsGroupComponent
import com.intellij.ide.plugins.newui.PluginsTab
import com.intellij.ide.plugins.newui.SearchWords
import com.intellij.ide.plugins.newui.TabbedPaneHeaderComponent
import com.intellij.ide.plugins.newui.UiPluginManager
import com.intellij.ide.util.PropertiesComponent
import com.intellij.idea.AppMode
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CheckedActionGroup
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.updateSettings.impl.PluginAutoUpdateListener
import com.intellij.openapi.updateSettings.impl.UpdateOptions
import com.intellij.openapi.updateSettings.impl.UpdateSettings
import com.intellij.openapi.updateSettings.impl.pluginsAdvertisement.FUSEventSource
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.NlsContexts
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.WelcomeScreen
import com.intellij.openapi.wm.impl.welcomeScreen.PluginsTabFactory
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenEventCollector
import com.intellij.platform.util.coroutines.childScope
import com.intellij.ui.ComponentUtil
import com.intellij.ui.GotItTooltip
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.components.labels.LinkListener
import com.intellij.ui.popup.ActionPopupOptions
import com.intellij.ui.popup.PopupFactoryImpl
import com.intellij.util.asDisposable
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.net.HttpProxyConfigurable
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.TextTransferable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import java.awt.Component
import java.awt.Dimension
import java.awt.Point
import java.util.function.Consumer
import javax.accessibility.AccessibleContext
import javax.accessibility.AccessibleRole
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants

@ApiStatus.Internal
class PluginManagerConfigurablePanel : Disposable {
  private val myCoroutineScope: CoroutineScope

  private val myPluginModelFacade: PluginModelFacade
  private var myPluginUpdatesService: PluginUpdatesService? = null
  private val myPluginManagerCustomizer: PluginManagerCustomizer? = PluginManagerCustomizer.getInstance()

  private var myTabHeaderComponent: TabbedPaneHeaderComponent? = null
  private val myInstalledTabHeaderUpdatesCountIcon: CountIcon = CountIcon()

  private var myMarketplaceTab: MarketplacePluginsTab? = null
  private var myInstalledTab: InstalledPluginsTab? = null
  private var myCardPanel: MultiPanel? = null

  private var myLaterSearchQuery: String? = null
  private var myForceShowInstalledTabForTag: Boolean = false
  private var myShowMarketplaceTab: Boolean = false

  private var myPluginsAutoUpdateEnabled: Boolean? = null

  @Volatile
  private var myDisposeStarted: Boolean = false

  private val myCallbackLock: Any = Any()
  private var myShutdownCallbackExecuted: Boolean = false

  init {
    myPluginModelFacade = PluginModelFacade(MyPluginModel(null))
    val parentScope = ApplicationManager.getApplication().getService(PluginManagerCoroutineScopeHolder::class.java).coroutineScope
    val childScope = parentScope.childScope(javaClass.name, Dispatchers.IO, true)
    myPluginModelFacade.getModel().coroutineScope = childScope
    myCoroutineScope = childScope
  }

  fun getCenterComponent(controller: Configurable.TopComponentController): JComponent {
    myPluginModelFacade.getModel().setTopController(controller)
    return myTabHeaderComponent!!
  }

  fun getTopComponent(): JComponent {
    return getCenterComponent(Configurable.TopComponentController.EMPTY)
  }

  @RequiresEdt
  fun init(searchQuery: String?) {
    myTabHeaderComponent = object : TabbedPaneHeaderComponent(createGearActions(), { index ->
      myCardPanel!!.select(index, true)
      storeSelectionTab(index)

      val query = if (index == MARKETPLACE_TAB) myInstalledTab!!.searchQuery else myMarketplaceTab!!.searchQuery
      if (index == MARKETPLACE_TAB) {
        myMarketplaceTab!!.searchQuery = query
      }
      else {
        myInstalledTab!!.searchQuery = query
      }
    }) {
      override fun uiDataSnapshot(sink: DataSink) {
        sink.set(PluginManagerConfigurable.PLUGIN_INSTALL_CALLBACK_DATA_KEY, Consumer { callbackData -> onPluginInstalledFromDisk(callbackData) })
      }
    }
    createGearGotIt()
    myLaterSearchQuery = searchQuery

    myTabHeaderComponent!!.addTab(IdeBundle.message("plugin.manager.tab.marketplace"), null)
    myTabHeaderComponent!!.addTab(IdeBundle.message("plugin.manager.tab.installed"), myInstalledTabHeaderUpdatesCountIcon)

    CustomPluginRepositoryService.getInstance().clearCache()
    myPluginUpdatesService = UiPluginManager.getInstance().subscribeToUpdatesCount(myPluginModelFacade.getModel().sessionId) { updatesCount ->
      ApplicationManager.getApplication().invokeLater {
        onPluginUpdatesRecalculation(updatesCount)
      }
    }
    myPluginModelFacade.getModel().pluginUpdatesService = myPluginUpdatesService!!

    UiPluginManager.getInstance().updateDescriptorsForInstalledPlugins()

    createMarketplaceTab()
    createInstalledTab()

    PluginManagerUsageCollector.sessionStarted()

    myCardPanel = object : MultiPanel() {
      override fun create(key: Int?): JComponent {
        if (key == MARKETPLACE_TAB) {
          return myMarketplaceTab!!.createPanel()
        }
        if (key == INSTALLED_TAB) {
          return myInstalledTab!!.createPanel()
        }
        return super.create(key)
      }
    }
    myCardPanel!!.minimumSize = JBDimension(580, 380)
    myCardPanel!!.preferredSize = JBDimension(800, 600)

    myTabHeaderComponent!!.setListener()

    val selectionTab = getStoredSelectionTab()
    myTabHeaderComponent!!.setSelection(selectionTab)
    myCardPanel!!.select(selectionTab, true)

    if (myLaterSearchQuery != null) {
      val search = enableSearch(myLaterSearchQuery, myForceShowInstalledTabForTag)
      if (search != null) {
        ApplicationManager.getApplication().invokeLater(search, ModalityState.any())
      }
      myLaterSearchQuery = null
      myForceShowInstalledTabForTag = false
    }

    if (myPluginManagerCustomizer != null) {
      myPluginManagerCustomizer.initCustomizer(myCardPanel!!)
    }
  }

  fun getComponent(): JComponent {
    return myCardPanel!!
  }

  fun isMarketplaceTabShowing(): Boolean {
    return myTabHeaderComponent!!.getSelectionTab() == MARKETPLACE_TAB
  }

  fun isInstalledTabShowing(): Boolean {
    return myTabHeaderComponent!!.getSelectionTab() == INSTALLED_TAB
  }

  private fun createGearActions(): DefaultActionGroup {
    val actions = DefaultActionGroup()
    if (PluginManagementPolicy.getInstance().isPluginAutoUpdateAllowed()) {
      val state = UpdateSettings.getInstance().getState()
      myPluginsAutoUpdateEnabled = state.isPluginsAutoUpdateEnabled

      val connect: MessageBusConnection = ApplicationManager.getApplication().getMessageBus()
        .connect(myCoroutineScope.asDisposable())
      connect.subscribe(PluginAutoUpdateListener.TOPIC, object : PluginAutoUpdateListener {
        override fun settingsChanged() {
          myPluginsAutoUpdateEnabled = state.isPluginsAutoUpdateEnabled
        }
      })

      actions.add(UpdatePluginsAutomaticallyToggleAction())
      actions.addSeparator()
    }
    actions.add(ManagePluginRepositoriesAction())
    actions.add(OpenHttpProxyConfigurableAction())
    actions.addSeparator()
    actions.add(ManagePluginCertificatesAction())

    actions.add(CustomInstallPluginFromDiskAction())
    if (myPluginManagerCustomizer != null) {
      actions.addAll(myPluginManagerCustomizer.getExtraPluginsActions())
    }
    actions.addSeparator()
    actions.add(ChangePluginStateAction(false))
    actions.add(ChangePluginStateAction(true))

    if (ApplicationManager.getApplication().isInternal) {
      actions.addSeparator()
      actions.add(ResetConfigurableAction())
    }
    return actions
  }

  private fun createGearGotIt() {
    if (!PluginManagementPolicy.getInstance().isPluginAutoUpdateAllowed() ||
        UpdateSettings.getInstance().getState().isPluginsAutoUpdateEnabled ||
        AppMode.isRemoteDevHost()) {
      return
    }

    val title = IdeBundle.message("plugin.manager.plugins.auto.update.title")
    val tooltip = GotItTooltip(title, IdeBundle.message("plugin.manager.plugins.auto.update.description"), this)
    tooltip.withHeader(title)
    tooltip.show(myTabHeaderComponent!!.getComponent(1) as JComponent) { component, _ ->
      Point(component.getWidth() / 2, (component as JComponent).visibleRect.height)
    }
  }

  private fun resetPanels() {
    CustomPluginRepositoryService.getInstance().clearCache()

    if (myMarketplaceTab != null) {
      myMarketplaceTab!!.resetCache()
    }

    myPluginUpdatesService!!.recalculateUpdates()

    if (myMarketplaceTab != null) {
      myMarketplaceTab!!.onPanelReset(myTabHeaderComponent!!.getSelectionTab() == MARKETPLACE_TAB)
    }
  }

  private fun onPluginUpdatesRecalculation(updatesCount: Int?) {
    val count = updatesCount ?: 0
    val text = Integer.toString(count)

    val tooltip = PluginUpdatesService.getUpdatesTooltip()
    myTabHeaderComponent!!.setTabTooltip(INSTALLED_TAB, tooltip)

    myInstalledTab!!.onPluginUpdatesRecalculation(updatesCount, tooltip)

    myInstalledTabHeaderUpdatesCountIcon.setText(text)
    myTabHeaderComponent!!.update()
  }

  private fun createMarketplaceTab() {
    myMarketplaceTab = MarketplacePluginsTab(myPluginModelFacade, myCoroutineScope, myPluginManagerCustomizer, myPluginUpdatesService!!)
  }

  private fun createInstalledTab() {
    myInstalledTab = InstalledPluginsTab(
      myPluginModelFacade,
      myPluginUpdatesService!!,
      myCoroutineScope,
      { _ -> myTabHeaderComponent!!.setSelectionWithEvents(MARKETPLACE_TAB) },
    )

    myPluginModelFacade.getModel().setCancelInstallCallback { descriptor ->
      val installedSearchPanel = myInstalledTab!!.getInstalledSearchPanel() ?: return@setCancelInstallCallback

      val group: PluginsGroup = installedSearchPanel.group

      if (group.ui != null && group.ui!!.findComponent(descriptor.pluginId) != null) {
        installedSearchPanel.panel.removeFromGroup(group, descriptor)
        group.titleWithCount()
        installedSearchPanel.fullRepaint()

        if (group.models.isEmpty()) {
          installedSearchPanel.removeGroup()
        }
      }
    }
  }

  @Suppress("SameParameterValue")
  fun setInstallSource(source: FUSEventSource?) {
    myPluginModelFacade.getModel().setInstallSource(source)
  }

  override fun dispose() {
    synchronized(myCallbackLock) {
      myDisposeStarted = true
    }

    if (ComponentUtil.getParentOfType(WelcomeScreen::class.java, myCardPanel!!) != null && isModified()) {
      scheduleApply()
    }
    val pluginsState = InstalledPluginsState.getInstance()
    if (myPluginModelFacade.getModel().toBackground()) {
      pluginsState.clearShutdownCallback()
    }

    if (myMarketplaceTab != null) {
      myMarketplaceTab!!.dispose()
    }

    if (myInstalledTab != null) {
      myInstalledTab!!.dispose()
    }

    if (myMarketplaceTab != null) {
      myMarketplaceTab!!.dispose()
    }

    if (myInstalledTab!!.getInstalledSearchPanel() != null) {
      myInstalledTab!!.getInstalledSearchPanel()!!.dispose()
    }

    myPluginUpdatesService!!.dispose()
    PluginPriceService.cancel()

    pluginsState.runShutdownCallback()
    pluginsState.resetChangesAppliedWithoutRestart()

    Disposer.dispose(this)
    myCoroutineScope.cancel(null)
  }

  fun cancel() {
    myPluginModelFacade.getModel().cancel(myCardPanel!!, true)
  }

  fun isModified(): Boolean {
    if (myPluginsAutoUpdateEnabled != null &&
        UpdateSettings.getInstance().getState().isPluginsAutoUpdateEnabled != myPluginsAutoUpdateEnabled) {
      return true
    }
    return myPluginModelFacade.getModel().isModified()
  }

  fun scheduleApply() {
    ApplicationManager.getApplication().invokeLater({
      try {
        apply()
        WelcomeScreenEventCollector.logPluginsModified()
        synchronized(myCallbackLock) {
          if (myDisposeStarted && !myShutdownCallbackExecuted) {
            InstalledPluginsState.getInstance().runShutdownCallback()
          }
        }
      }
      catch (exception: ConfigurationException) {
        Logger.getInstance(PluginsTabFactory::class.java).error(exception)
      }
    }, ModalityState.nonModal())
  }

  @Throws(ConfigurationException::class)
  fun apply() {
    if (myPluginsAutoUpdateEnabled != null) {
      val state: UpdateOptions = UpdateSettings.getInstance().getState()
      if (state.isPluginsAutoUpdateEnabled != myPluginsAutoUpdateEnabled) {
        UiPluginManager.getInstance().setPluginsAutoUpdateEnabled(myPluginsAutoUpdateEnabled!!)
      }
    }

    myPluginModelFacade.getModel().applyWithCallback(myCardPanel!!) { installedWithoutRestart ->
      if (installedWithoutRestart) {
        return@applyWithCallback
      }
      val installedPluginsState = InstalledPluginsState.getInstance()

      synchronized(myCallbackLock) {
        if (myShutdownCallbackExecuted) {
          return@applyWithCallback
        }

        if (myPluginModelFacade.getModel().createShutdownCallback) {
          installedPluginsState.setShutdownCallback {
            synchronized(myCallbackLock) {
              if (myShutdownCallbackExecuted) {
                return@setShutdownCallback
              }
              myShutdownCallbackExecuted = true
            }

            ApplicationManager.getApplication().invokeLater {
              if (ApplicationManager.getApplication().isExitInProgress) return@invokeLater // already shutting down
              if (myPluginManagerCustomizer != null) {
                myPluginManagerCustomizer.requestRestart(myPluginModelFacade, myTabHeaderComponent!!)
                return@invokeLater
              }
              myPluginModelFacade.closeSession()
              PluginManagerConfigurable.shutdownOrRestartApp()
            }
          }
        }
      }

      synchronized(myCallbackLock) {
        if (myDisposeStarted && !myShutdownCallbackExecuted) {
          installedPluginsState.runShutdownCallback()
        }
      }
    }
  }

  fun reset() {
    if (myPluginsAutoUpdateEnabled != null) {
      myPluginsAutoUpdateEnabled = UpdateSettings.getInstance().getState().isPluginsAutoUpdateEnabled
    }
    myPluginModelFacade.getModel().clear(myCardPanel!!)
  }

  fun selectAndEnable(descriptors: Set<IdeaPluginDescriptor>) {
    myPluginModelFacade.getModel().enable(descriptors)
    select(descriptors.map { it.pluginId })
  }

  fun select(pluginIds: Collection<PluginId>) {
    updateSelectionTab(INSTALLED_TAB)

    val components = ArrayList<ListPluginComponent>()

    for (pluginId in pluginIds) {
      val component = findInstalledPluginById(pluginId)
      if (component != null) {
        components.add(component)
      }
    }

    if (!components.isEmpty()) {
      myInstalledTab!!.getInstalledPanel()!!.setSelection(components)
    }
  }

  fun enableSearch(option: String?): Runnable? {
    return enableSearch(option, false)
  }

  fun enableSearch(option: String?, ignoreTagMarketplaceTab: Boolean): Runnable? {
    if (StringUtil.isEmpty(option) &&
        (myTabHeaderComponent!!.getSelectionTab() == MARKETPLACE_TAB || myInstalledTab!!.getInstalledSearchPanel()!!.isQueryEmpty)) {
      return null
    }

    return Runnable {
      var marketplace = !ignoreTagMarketplaceTab && option != null && option.startsWith(SearchWords.TAG.value)
      if (myShowMarketplaceTab) {
        marketplace = true
        myShowMarketplaceTab = false
      }
      updateSelectionTab(if (marketplace) MARKETPLACE_TAB else INSTALLED_TAB)

      val tab: PluginsTab = if (marketplace) myMarketplaceTab!! else myInstalledTab!!
      tab.clearSearchPanel(option ?: "")

      if (!StringUtil.isEmpty(option)) {
        tab.showSearchPanel(option!!)
      }
    }
  }

  fun openMarketplaceTab(option: String) {
    myLaterSearchQuery = option
    myShowMarketplaceTab = true
    if (myTabHeaderComponent != null) {
      updateSelectionTab(MARKETPLACE_TAB)
    }
    if (myMarketplaceTab != null) {
      myMarketplaceTab!!.clearSearchPanel(option)
      myMarketplaceTab!!.showSearchPanel(option)
    }
  }

  fun openInstalledTab(option: String) {
    myLaterSearchQuery = option
    myShowMarketplaceTab = false
    myForceShowInstalledTabForTag = true
    if (myTabHeaderComponent != null) {
      updateSelectionTab(INSTALLED_TAB)
    }
  }

  @RequiresEdt
  private fun onPluginInstalledFromDisk(callbackData: PluginInstallCallbackData) {
    PluginModelAsyncOperationsExecutor.updateErrors(
      myCoroutineScope,
      myPluginModelFacade.getModel().sessionId,
      callbackData.pluginDescriptor.pluginId,
    ) { errors ->
      updateAfterPluginInstalledFromDisk(callbackData, errors)
    }
  }

  private fun updateAfterPluginInstalledFromDisk(callbackData: PluginInstallCallbackData, errors: List<HtmlChunk>) {
    myPluginModelFacade.getModel().pluginInstalledFromDisk(callbackData, errors)

    val select = myInstalledTab!!.getInstalledPanel() == null
    updateSelectionTab(INSTALLED_TAB)

    myInstalledTab!!.clearSearchPanel("")

    val component = if (select) findInstalledPluginById(callbackData.pluginDescriptor.pluginId) else null
    if (component != null) {
      myInstalledTab!!.getInstalledPanel()!!.setSelection(component)
    }
  }

  private fun updateSelectionTab(tab: Int) {
    if (myTabHeaderComponent!!.getSelectionTab() != tab) {
      myTabHeaderComponent!!.setSelectionWithEvents(tab)
    }
  }

  private fun findInstalledPluginById(pluginId: PluginId): ListPluginComponent? {
    for (group in myInstalledTab!!.getInstalledGroups()!!) {
      val component = group.findComponent(pluginId)
      if (component != null) {
        return component
      }
    }
    return null
  }

  open class LinkLabelButton<T> : LinkLabel<T> {
    constructor(@NlsContexts.LinkLabel text: String, icon: Icon?) : super(text, icon)

    @Suppress("UNCHECKED_CAST")
    constructor(@NlsContexts.LinkLabel text: String, icon: Icon?, aListener: LinkListener<*>?) : super(text, icon, aListener as LinkListener<T>?)

    @Suppress("UNCHECKED_CAST")
    constructor(
      @NlsContexts.LinkLabel text: String,
      icon: Icon?,
      aListener: LinkListener<*>?,
      aLinkData: T?,
    ) : super(text, icon, aListener as LinkListener<T>?, aLinkData)

    override fun getAccessibleContext(): AccessibleContext {
      if (accessibleContext == null) {
        accessibleContext = AccessibleLinkLabelButton()
      }
      return accessibleContext
    }

    protected inner class AccessibleLinkLabelButton : AccessibleLinkLabel() {
      override fun getAccessibleRole(): AccessibleRole {
        return AccessibleRole.PUSH_BUTTON
      }
    }
  }

  private inner class ManagePluginRepositoriesAction : DumbAwareAction(IdeBundle.message("plugin.manager.repositories")) {
    override fun actionPerformed(e: AnActionEvent) {
      val oldRepoUrls = ArrayList(UpdateSettings.getInstance().getStoredPluginHosts())
      if (ShowSettingsUtil.getInstance().editConfigurable(myCardPanel, PluginHostsConfigurable())) {
        if (myPluginManagerCustomizer == null) {
          resetPanels()
        }

          val customizer = PluginManagerCustomizer.getInstance()
        if (customizer != null) {
          val newRepoUrls = UpdateSettings.getInstance().getStoredPluginHosts()
          val addedRepoUrls = ArrayList(newRepoUrls)
          addedRepoUrls.removeAll(oldRepoUrls)
          val removedRepoUrls = ArrayList(oldRepoUrls)
          removedRepoUrls.removeAll(newRepoUrls)
          customizer.updateCustomRepositories(addedRepoUrls, removedRepoUrls) {
            resetPanels()
          }
        }
      }
    }
  }

  private inner class OpenHttpProxyConfigurableAction : DumbAwareAction(IdeBundle.message("button.http.proxy.settings")) {
    override fun actionPerformed(e: AnActionEvent) {
      if (HttpProxyConfigurable.editConfigurable(myCardPanel)) {
        resetPanels()
      }
    }
  }

  private inner class ManagePluginCertificatesAction : DumbAwareAction(IdeBundle.message("plugin.manager.custom.certificates")) {
    override fun actionPerformed(e: AnActionEvent) {
      if (ShowSettingsUtil.getInstance().editConfigurable(myCardPanel, PluginCertificateManager())) {
        resetPanels()
      }
    }
  }

  private inner class CustomInstallPluginFromDiskAction : InstallFromDiskAction(
    this@PluginManagerConfigurablePanel.myPluginModelFacade.getModel(),
    this@PluginManagerConfigurablePanel.myPluginModelFacade.getModel(),
    this@PluginManagerConfigurablePanel.myCardPanel,
  ) {
    @RequiresEdt
    override fun onPluginInstalledFromDisk(callbackData: PluginInstallCallbackData, project: Project?) {
      if (myPluginManagerCustomizer != null) {
        myPluginManagerCustomizer.updateAfterModification {
          this@PluginManagerConfigurablePanel.onPluginInstalledFromDisk(callbackData)
        }
        return
      }
      this@PluginManagerConfigurablePanel.onPluginInstalledFromDisk(callbackData)
    }
  }

  private inner class ResetConfigurableAction : DumbAwareAction(IdeBundle.message("plugin.manager.refresh")) {
    override fun actionPerformed(e: AnActionEvent) {
      resetPanels()
    }
  }

  private inner class UpdatePluginsAutomaticallyToggleAction : DumbAwareToggleAction(IdeBundle.message("updates.plugins.autoupdate.settings.action")) {
    override fun isSelected(e: AnActionEvent): Boolean {
      return myPluginsAutoUpdateEnabled!!
    }

    override fun setSelected(e: AnActionEvent, state: Boolean) {
      myPluginsAutoUpdateEnabled = state
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
      return ActionUpdateThread.EDT
    }
  }

  private class GroupByActionGroup : DefaultActionGroup(), CheckedActionGroup

  private inner class ChangePluginStateAction(enable: Boolean) : DumbAwareAction(
    if (enable) IdeBundle.message("plugins.configurable.enable.all.downloaded")
    else IdeBundle.message("plugins.configurable.disable.all.downloaded"),
  ) {
    private val myEnable: Boolean = enable

    override fun actionPerformed(e: AnActionEvent) {
      PluginModelAsyncOperationsExecutor.switchPlugins(myCoroutineScope, myPluginModelFacade, myEnable) { models ->
        //noinspection unchecked
        setState(myPluginModelFacade, models as Collection<PluginUiModel>, myEnable)
      }
    }
  }

  companion object {
    private const val MARKETPLACE_TAB: Int = 0
    private const val INSTALLED_TAB: Int = 1

    @JvmStatic
    fun showRightBottomPopup(component: Component, @Nls title: String, group: ActionGroup) {
      val actions = GroupByActionGroup()
      actions.addSeparator("  " + title)
      actions.addAll(group)

      val context: DataContext = DataManager.getInstance().getDataContext(component)

      val popup: JBPopup = object : PopupFactoryImpl.ActionGroupPopup(
        null,
        null,
        actions,
        context,
        ActionPlaces.POPUP,
        PresentationFactory(),
        ActionPopupOptions.honorMnemonics(),
        null,
      ) {}
      popup.addListener(object : JBPopupListener {
        override fun beforeShown(event: LightweightWindowEvent) {
          val location = component.locationOnScreen
          val size: Dimension = popup.size
          popup.setLocation(Point(location.x + component.width - size.width, location.y + component.height))
        }
      })
      popup.show(component)
    }

    private fun getStoredSelectionTab(): Int {
      val value = PropertiesComponent.getInstance().getInt(PluginManagerConfigurable.SELECTION_TAB_KEY, MARKETPLACE_TAB)
      return if (value < MARKETPLACE_TAB || value > INSTALLED_TAB) MARKETPLACE_TAB else value
    }

    private fun storeSelectionTab(value: Int) {
      PropertiesComponent.getInstance().setValue(PluginManagerConfigurable.SELECTION_TAB_KEY, value, MARKETPLACE_TAB)
    }

    /** Modifies the state of the plugin list, excluding Ultimate plugins when the Ultimate license is not active. */
    @JvmStatic
    fun setState(pluginModelFacade: PluginModelFacade, models: Collection<PluginUiModel>, isEnable: Boolean) {
      if (models.isEmpty()) return

      val pluginsRequiringUltimate = UiPluginManager.getInstance()
        .filterPluginsRequiringUltimateButItsDisabled(models.map { it.pluginId })
      val suitableDescriptors = models.filter { descriptor -> !pluginsRequiringUltimate.contains(descriptor.pluginId) }

      if (suitableDescriptors.isEmpty()) return

      if (isEnable) {
        pluginModelFacade.enable(suitableDescriptors)
      }
      else {
        pluginModelFacade.disable(suitableDescriptors)
      }
    }

    @JvmStatic
    fun clearUpdates(panel: PluginsGroupComponent) {
      for (group in panel.groups) {
        for (plugin in group.plugins) {
          plugin.setUpdateDescriptor(null as IdeaPluginDescriptor?)
        }
      }
    }

    @JvmStatic
    fun applyUpdates(panel: PluginsGroupComponent, updates: Collection<PluginUiModel>) {
      for (descriptor in updates) {
        for (group in panel.groups) {
          val component = group.findComponent(descriptor.pluginId)
          if (component != null) {
            component.setUpdateDescriptor(descriptor)
            break
          }
        }
      }
    }

    @JvmStatic
    fun registerCopyProvider(component: PluginsGroupComponent) {
      val copyProvider = object : CopyProvider {
        override fun performCopy(dataContext: DataContext) {
          val text = StringUtil.join(
            component.selection,
            { pluginComponent: ListPluginComponent ->
              val model = pluginComponent.pluginModel
              String.format("%s (%s)", model.name, model.version)
            },
            "\n",
          )
          CopyPasteManager.getInstance().setContents(TextTransferable(text as String?))
        }

        override fun getActionUpdateThread(): ActionUpdateThread {
          return ActionUpdateThread.EDT
        }

        override fun isCopyEnabled(dataContext: DataContext): Boolean {
          return component.selection.isNotEmpty()
        }

        override fun isCopyVisible(dataContext: DataContext): Boolean {
          return true
        }
      }

      DataManager.registerDataProvider(component) { dataId ->
        if (PlatformDataKeys.COPY_PROVIDER.`is`(dataId)) copyProvider else null
      }
    }

    @JvmStatic
    fun createScrollPane(panel: PluginsGroupComponent, initSelection: Boolean): JComponent {
      val pane = JBScrollPane(panel, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
      pane.border = JBUI.Borders.empty()
      if (initSelection) {
        panel.initialSelection()
      }
      return pane
    }
  }
}
