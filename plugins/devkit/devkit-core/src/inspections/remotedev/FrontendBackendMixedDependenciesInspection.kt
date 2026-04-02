// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.util.xml.DomElement
import com.intellij.util.xml.highlighting.DomElementAnnotationHolder
import com.intellij.util.xml.highlighting.DomHighlightingHelper
import org.jetbrains.idea.devkit.DevKitBundle
import org.jetbrains.idea.devkit.dom.IdeaPlugin
import org.jetbrains.idea.devkit.inspections.DevKitPluginXmlInspectionBase

internal class FrontendBackendMixedDependenciesInspection : DevKitPluginXmlInspectionBase() {
  private val restrictionsService = ApiRestrictionsService.getInstance()

  override fun isAllowed(holder: DomElementAnnotationHolder): Boolean {
    if (!super.isAllowed(holder)) return false

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

    val matchedDependencies = buildList {
      dependencies.moduleEntry.forEach { moduleDescriptor ->
        moduleDescriptor.name.stringValue?.let { dependencyName ->
          restrictionsService.getDependencyKind(dependencyName)?.let { dependencyKind ->
            add(MatchedDependency(dependencyName, dependencyKind, moduleDescriptor))
          }
        }
      }
      dependencies.plugin.forEach { pluginDescriptor ->
        pluginDescriptor.id.stringValue?.let { dependencyName ->
          restrictionsService.getDependencyKind(dependencyName)?.let { dependencyKind ->
            add(MatchedDependency(dependencyName, dependencyKind, pluginDescriptor))
          }
        }
      }
    }
    val frontendDependencies = matchedDependencies.filter { it.kind == ApiRestrictionsService.ModuleKind.FRONTEND }
    val backendDependencies = matchedDependencies.filter { it.kind == ApiRestrictionsService.ModuleKind.BACKEND }
    if (frontendDependencies.isEmpty() || backendDependencies.isEmpty()) return

    val message = DevKitBundle.message(
      "inspection.remote.dev.mixed.dependencies.message",
      frontendDependencies.joinToString { it.name },
      backendDependencies.joinToString { it.name },
    )
    matchedDependencies.forEach { matchedDependency ->
      holder.createProblem(matchedDependency.element, message).highlightWholeElement()
    }
  }

  private data class MatchedDependency(
    val name: String,
    val kind: ApiRestrictionsService.ModuleKind,
    val element: DomElement,
  )
}
