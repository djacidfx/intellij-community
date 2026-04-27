// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.util.xml.DomElement
import com.intellij.util.xml.highlighting.DomElementAnnotationHolder
import com.intellij.util.xml.highlighting.DomHighlightingHelper
import org.jetbrains.idea.devkit.dom.IdeaPlugin
import org.jetbrains.idea.devkit.inspections.DevKitPluginXmlInspectionBase
import org.jetbrains.idea.devkit.DevKitBundle.message

internal class MissingFrontendOrBackendRuntimeDependencyInspection : DevKitPluginXmlInspectionBase() {

  private val moduleNameSuffixToRequiredRuntimeDependency = listOf(
    ".frontend" to "intellij.platform.frontend",
    ".backend" to "intellij.platform.backend",
  )

  private val coreModuleNames = moduleNameSuffixToRequiredRuntimeDependency.map { it.second }

  override fun checkDomElement(element: DomElement, holder: DomElementAnnotationHolder, helper: DomHighlightingHelper) {
    if (element !is IdeaPlugin) return
    if (!isAllowed(holder)) return
    val containingFileName = element.xmlElement?.containingFile?.name ?: return
    if (containingFileName == "plugin.xml") return // do not check in main modules
    if (!containingFileName.startsWith("intellij.")) return // check only intellij modules

    val currentModuleName = containingFileName.removeSuffix(".xml")
    if (currentModuleName in coreModuleNames) return

    for ((moduleNameSuffix, requiredRuntimeDependency) in moduleNameSuffixToRequiredRuntimeDependency) {
      if (currentModuleName.endsWith(moduleNameSuffix)) {
        val dependencies = element.dependencies
        if (!SplitModePluginDependencyUtil.hasTransitiveDependency(element, requiredRuntimeDependency)) {
          val reportedElement = if (dependencies.exists()) dependencies else element
          holder.createProblem(
            reportedElement,
            message(
              "inspection.remote.dev.missing.runtime.dependency.message",
              currentModuleName, moduleNameSuffix, requiredRuntimeDependency
            ),
            SplitModeDependencyQuickFixes.createAddExplicitDependencyFix(moduleKindByDependencyName(requiredRuntimeDependency))
          )
        }
        return // only one module name suffix can be matched, so don't check more
      }
    }
  }

  private fun moduleKindByDependencyName(dependencyName: String): SplitModeApiRestrictionsService.ModuleKind {
    return when (dependencyName) {
      "intellij.platform.frontend" -> SplitModeApiRestrictionsService.ModuleKind.FRONTEND
      "intellij.platform.backend" -> SplitModeApiRestrictionsService.ModuleKind.BACKEND
      else -> error("Unsupported split-mode runtime dependency: $dependencyName")
    }
  }
}
