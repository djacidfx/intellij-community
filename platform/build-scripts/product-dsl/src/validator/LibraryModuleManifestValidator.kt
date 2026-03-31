// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.productLayout.validator

import org.jetbrains.intellij.build.productLayout.LIB_MODULE_PREFIX
import org.jetbrains.intellij.build.productLayout.pipeline.ComputeContext
import org.jetbrains.intellij.build.productLayout.pipeline.NodeIds
import org.jetbrains.intellij.build.productLayout.pipeline.PipelineNode
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModule

/**
 * Library wrapper MANIFEST validation.
 *
 * Purpose: Ensure every library wrapper module (`intellij.libraries.*`) has a MANIFEST with a stable automatic module name.
 * Inputs: all JPS modules from outputProvider.
 * Output: `resources/META-INF/MANIFEST.mf` updates.
 * Auto-fix: yes.
 *
 * Glossary: docs/validators/README.md.
 * Spec: docs/validators/library-module-manifest.md.
 */
internal object LibraryModuleManifestValidator : PipelineNode {
  override val id get() = NodeIds.LIBRARY_MODULE_MANIFEST_VALIDATION

  override suspend fun execute(ctx: ComputeContext) {
    val model = ctx.model
    if (model.updateSuppressions) {
      return
    }

    for (module in model.outputProvider.getAllModules().asSequence().filter(::isLibraryWrapperModule).sortedBy { it.name }) {
      val manifestPath = model.outputProvider.getModuleImlFile(module).parent.resolve(MANIFEST_RELATIVE_PATH)
      model.fileUpdater.updateIfChanged(manifestPath, renderManifest(module.name))
    }
  }
}

private const val MANIFEST_RELATIVE_PATH: String = "resources/META-INF/MANIFEST.mf"

private fun isLibraryWrapperModule(module: JpsModule): Boolean {
  return module.name.startsWith(LIB_MODULE_PREFIX) && module.sourceRoots.none { it.rootType == JavaSourceRootType.SOURCE }
}

private fun renderManifest(moduleName: String): String {
  return "Automatic-Module-Name: $moduleName\n"
}
