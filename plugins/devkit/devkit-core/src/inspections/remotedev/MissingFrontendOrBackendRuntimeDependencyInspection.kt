// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.openapi.project.Project
import com.intellij.util.xml.DomElement
import com.intellij.util.xml.DomUtil
import com.intellij.util.xml.highlighting.DomElementAnnotationHolder
import com.intellij.util.xml.highlighting.DomHighlightingHelper
import org.jetbrains.idea.devkit.DevKitBundle.message
import org.jetbrains.idea.devkit.dom.DependencyDescriptor
import org.jetbrains.idea.devkit.dom.IdeaPlugin
import org.jetbrains.idea.devkit.inspections.DevKitPluginXmlInspectionBase

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
            AddDependencyFix(requiredRuntimeDependency)
          )
        }
        return // only one module name suffix can be matched, so don't check more
      }
    }
  }

  private class AddDependencyFix(private val dependencyName: String) : LocalQuickFix {

    override fun getFamilyName(): String {
      return message("inspection.remote.dev.missing.runtime.dependency.fix.add", dependencyName)
    }

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
      val domElement = DomUtil.getDomElement(descriptor.psiElement) ?: return
      val dependencies = when (domElement) {
        is IdeaPlugin -> domElement.dependencies
        is DependencyDescriptor -> domElement
        else -> return
      }
      val newModuleEntry = dependencies.addModuleEntry()
      newModuleEntry.name.stringValue = dependencyName
    }
  }
}
