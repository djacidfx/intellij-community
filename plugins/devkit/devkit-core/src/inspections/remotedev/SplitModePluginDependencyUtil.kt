// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import org.jetbrains.idea.devkit.dom.IdeaPlugin
import java.util.concurrent.ConcurrentHashMap

internal object SplitModePluginDependencyUtil {
  fun collectTransitiveDependencyNames(parsedXmlDescriptor: IdeaPlugin): Set<String> {
    return getCachedGraph(parsedXmlDescriptor.manager.project).collectTransitiveDependencyNames(parsedXmlDescriptor)
  }

  fun hasTransitiveDependency(ideaPlugin: IdeaPlugin, dependencyName: String): Boolean {
    return dependencyName in collectTransitiveDependencyNames(ideaPlugin)
  }

  private fun getCachedGraph(project: Project): SplitModePluginDependencyGraph {
    return CachedValuesManager.getManager(project).getCachedValue(project) {
      CachedValueProvider.Result.create(
        SplitModePluginDependencyGraph(),
        ProjectRootModificationTracker.getInstance(project),
        PsiManager.getInstance(project).modificationTracker.forLanguage(XMLLanguage.INSTANCE),
      )
    }
  }
}

private class SplitModePluginDependencyGraph {
  private val dependencyNamesByFile = ConcurrentHashMap<VirtualFile, Set<String>>()

  fun collectTransitiveDependencyNames(ideaPlugin: IdeaPlugin): Set<String> {
    val descriptorFile = ideaPlugin.descriptorVirtualFile()
    if (descriptorFile != null) {
      dependencyNamesByFile[descriptorFile]?.let { return it }
    }

    val dependencyNames = collectTransitiveDependencyNames(ideaPlugin, HashSet())
    if (descriptorFile != null) {
      dependencyNamesByFile.putIfAbsent(descriptorFile, dependencyNames)
    }
    return dependencyNames
  }

  private fun collectTransitiveDependencyNames(ideaPlugin: IdeaPlugin, visitedFiles: MutableSet<VirtualFile>): Set<String> {
    val descriptorFile = ideaPlugin.descriptorVirtualFile()
    if (descriptorFile != null) {
      dependencyNamesByFile[descriptorFile]?.let { return it }
      if (!visitedFiles.add(descriptorFile)) {
        return emptySet()
      }
    }

    val dependencyNames = LinkedHashSet<String>()
    collectDirectDependencies(ideaPlugin).forEach { (dependencyName, dependencyPlugin) ->
      dependencyNames.add(dependencyName)
      if (dependencyPlugin != null) {
        dependencyNames.addAll(collectTransitiveDependencyNames(dependencyPlugin, visitedFiles))
      }
    }

    val result = dependencyNames.toSet()
    if (descriptorFile != null) {
      visitedFiles.remove(descriptorFile)
      dependencyNamesByFile.putIfAbsent(descriptorFile, result)
    }
    return result
  }

  private fun collectDirectDependencies(ideaPlugin: IdeaPlugin): Sequence<Pair<String, IdeaPlugin?>> = sequence {
    ideaPlugin.depends.forEach { dependency ->
      val dependencyName = dependency.rawText ?: dependency.stringValue
      if (dependencyName != null) {
        yield(dependencyName to dependency.value)
      }
    }

    val dependencies = ideaPlugin.dependencies
    if (!dependencies.isValid) {
      return@sequence
    }

    dependencies.moduleEntry.forEach { moduleDescriptor ->
      val dependencyName = moduleDescriptor.name.stringValue
      if (dependencyName != null) {
        yield(dependencyName to moduleDescriptor.name.value)
      }
    }
    dependencies.plugin.forEach { pluginDescriptor ->
      val dependencyName = pluginDescriptor.id.stringValue
      if (dependencyName != null) {
        yield(dependencyName to pluginDescriptor.id.value)
      }
    }
  }
}

private fun IdeaPlugin.descriptorVirtualFile(): VirtualFile? {
  return xmlElement?.containingFile?.originalFile?.virtualFile
}
