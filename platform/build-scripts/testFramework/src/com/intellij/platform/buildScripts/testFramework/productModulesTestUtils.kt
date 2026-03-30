// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.buildScripts.testFramework

import com.intellij.platform.runtime.product.serialization.ProductModulesSerialization
import com.intellij.platform.runtime.product.serialization.RawProductModules
import com.intellij.platform.runtime.product.serialization.ResourceFileResolver
import com.intellij.platform.runtime.repository.MalformedRepositoryException
import com.intellij.platform.runtime.repository.RuntimeModuleId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.jetbrains.intellij.build.BuildContext
import java.io.IOException
import java.io.InputStream

internal fun loadRawProductModulesFromOutput(productModulesModule: String, context: BuildContext): RawProductModules {
  val relativePath = "META-INF/$productModulesModule/product-modules.xml"
  val debugName = "($relativePath file in $productModulesModule)"
  val content = readFileContentFromModuleOutput(context = context, moduleName = productModulesModule, relativePath = relativePath)
                ?: throw MalformedRepositoryException("File '$relativePath' is not found in module $productModulesModule output")
  try {
    return ProductModulesSerialization.readProductModulesAndMergeIncluded(
      content.inputStream(),
      debugName,
      createModuleOutputResourceFileResolver(context),
    )
  }
  catch (e: IOException) {
    throw MalformedRepositoryException("Failed to load module group from $debugName", e)
  }
}

private fun createModuleOutputResourceFileResolver(context: BuildContext): ResourceFileResolver {
  return object : ResourceFileResolver {
    override fun readResourceFile(moduleId: RuntimeModuleId, relativePath: String): InputStream? {
      return readFileContentFromModuleOutput(context = context, moduleName = moduleId.name, relativePath = relativePath)?.inputStream()
    }

    override fun toString(): String {
      return "module output based resolver for '${context.paths.projectHome}' project"
    }
  }
}

private fun readFileContentFromModuleOutput(context: BuildContext, moduleName: String, relativePath: String): ByteArray? {
  @Suppress("RAW_RUN_BLOCKING")
  return runBlocking(Dispatchers.IO) {
    context.outputProvider.readFileContentFromModuleOutput(context.findRequiredModule(moduleName), relativePath)
  }
}
