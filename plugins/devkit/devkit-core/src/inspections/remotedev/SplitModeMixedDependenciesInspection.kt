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

    val dependencies = element.dependencies
    if (!dependencies.exists()) return

    val declaredDependencies = buildList {
      dependencies.moduleEntry.forEach { moduleDescriptor ->
        moduleDescriptor.name.stringValue?.let { dependencyName ->
          add(DeclaredDependency(dependencyName, moduleDescriptor))
        }
      }
      dependencies.plugin.forEach { pluginDescriptor ->
        pluginDescriptor.id.stringValue?.let { dependencyName ->
          add(DeclaredDependency(dependencyName, pluginDescriptor))
        }
      }
    }

    val moduleName = ModuleUtilCore.findModuleForFile(holder.fileElement.file)?.name ?: return
    val dependencyAnalysis = SplitModeModuleKindResolver.analyzeModuleDependencies(moduleName, declaredDependencies.map { it.name }.toSet())
    if (!dependencyAnalysis.hasMixedDependencies) return

    val message = DevKitBundle.message(
      "inspection.remote.dev.mixed.dependencies.message",
      dependencyAnalysis.frontendDependencies.joinToString(),
      dependencyAnalysis.backendDependencies.joinToString(),
    )
    declaredDependencies
      .filter { dependency ->
        dependency.name in dependencyAnalysis.frontendDependencies || dependency.name in dependencyAnalysis.backendDependencies
      }
      .forEach { dependency ->
        holder.createProblem(dependency.element, message).highlightWholeElement()
    }
  }

  private data class DeclaredDependency(
    val name: String,
    val element: DomElement,
  )
}
