// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.util.xml.DomElement
import com.intellij.util.xml.highlighting.DomElementAnnotationHolder
import com.intellij.util.xml.highlighting.DomHighlightingHelper
import org.jetbrains.idea.devkit.DevKitBundle.message
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

    val moduleAnalysis = SplitModeModuleKindResolver.getOrComputeModuleAnalysis(element.module ?: return)
    if (moduleAnalysis.resolvedModuleKind.kind != SplitModeApiRestrictionsService.ModuleKind.MIXED) return

    val dependencyAnalysis = moduleAnalysis.dependencyAnalysis
    val declaredDependencies = moduleAnalysis.declaredDependencies
    val highlightedDependencies = ArrayList<DependencyInfo>()
    for (dependency in declaredDependencies) {
      if (dependencyAnalysis.containsDependency(dependency.name)) {
        highlightedDependencies.add(dependency)
      }
    }
    val mixedDependenciesMessage = message(
      "inspection.remote.dev.mixed.dependencies.message",
      dependencyAnalysis.getFrontendDependencyNames(),
      dependencyAnalysis.getBackendDependencyNames(),
    )
    if (highlightedDependencies.isEmpty()) {
      if (!element.dependencies.exists()) {
        holder.createProblem(element, ProblemHighlightType.GENERIC_ERROR, mixedDependenciesMessage, null)
      }
      return
    }

    for (dependency in highlightedDependencies) {
      holder.createProblem(dependency.element ?: continue, ProblemHighlightType.GENERIC_ERROR, mixedDependenciesMessage, null).highlightWholeElement()
    }
  }
}
