// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.util.xml.DomElement
import com.intellij.util.xml.highlighting.DomElementAnnotationHolder
import com.intellij.util.xml.highlighting.DomHighlightingHelper
import org.jetbrains.idea.devkit.DevKitBundle
import org.jetbrains.idea.devkit.dom.IdeaPlugin
import org.jetbrains.idea.devkit.inspections.DevKitPluginXmlInspectionBase

internal class SplitModeMixedDependenciesInspection : DevKitPluginXmlInspectionBase() {
  private val restrictionsService = SplitModeApiRestrictionsService.getInstance()

  override fun isAllowed(holder: DomElementAnnotationHolder): Boolean {
    if (!super.isAllowed(holder)) return false
    if (SplitModeInspectionUtil.shouldSuppressForSingleModuleExternalPlugin(holder.fileElement.file)) return false

    if (restrictionsService.isLoaded()) {
      return true
    }

    restrictionsService.scheduleLoadRestrictions()
    return false
  }

  override fun checkDomElement(element: DomElement, holder: DomElementAnnotationHolder, helper: DomHighlightingHelper) {
    if (element !is IdeaPlugin) return
    if (!isAllowed(holder)) return

    if (!element.dependencies.exists()) return
    val inspectedFile = holder.fileElement.file
    if (SplitModeModuleKindResolver.getOrComputeModuleKind(inspectedFile)?.kind != SplitModeApiRestrictionsService.ModuleKind.MIXED) return

    val dependencyAnalysis = SplitModeModuleKindResolver.getOrComputeDependencyAnalysis(inspectedFile) ?: return
    val moduleName = ModuleUtilCore.findModuleForFile(inspectedFile)?.name ?: return
    val declaredDependencies = collectDeclaredDependencies(element, moduleName)
    val highlightedDependencies = ArrayList<DependencyInfo>()
    for (dependency in declaredDependencies) {
      if (dependencyAnalysis.containsDependency(dependency.name)) {
        highlightedDependencies.add(dependency)
      }
    }
    if (highlightedDependencies.isEmpty()) return

    val message = DevKitBundle.message(
      "inspection.remote.dev.mixed.dependencies.message",
      dependencyAnalysis.getFrontendDependencyNames(),
      dependencyAnalysis.getBackendDependencyNames(),
    )

    for (dependency in highlightedDependencies) {
      holder.createProblem(dependency.element ?: continue, message).highlightWholeElement()
    }
  }

  private fun collectDeclaredDependencies(element: IdeaPlugin, moduleName: String): List<DependencyInfo> {
    val declaredDependencies = ArrayList<DependencyInfo>()

    for (moduleDescriptor in element.dependencies.moduleEntry) {
      val dependencyName = moduleDescriptor.name.stringValue
      if (dependencyName != null) {
        declaredDependencies.add(
          DependencyInfo(
            dependencyName,
            "declared in module '$moduleName'",
            true,
            moduleDescriptor,
          )
        )
      }
    }
    for (pluginDescriptor in element.dependencies.plugin) {
      val dependencyName = pluginDescriptor.id.stringValue
      if (dependencyName != null) {
        declaredDependencies.add(
          DependencyInfo(
            dependencyName,
            "declared in module '$moduleName'",
            true,
            pluginDescriptor,
          )
        )
      }
    }

    return declaredDependencies
  }
}
