// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.productLayout.validator

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.intellij.build.productLayout.TestFailureLogger
import org.jetbrains.intellij.build.productLayout.dependency.jpsProject
import org.jetbrains.intellij.build.productLayout.dependency.pluginGraph
import org.jetbrains.intellij.build.productLayout.dependency.runValidationRule
import org.jetbrains.intellij.build.productLayout.dependency.testGenerationModel
import org.jetbrains.intellij.build.productLayout.discovery.ModuleSetGenerationConfig
import org.jetbrains.intellij.build.productLayout.model.error.FileChangeType
import org.jetbrains.intellij.build.productLayout.pipeline.GenerationPipeline
import org.jetbrains.intellij.build.productLayout.util.DeferredFileUpdater
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@ExtendWith(TestFailureLogger::class)
class LibraryModuleManifestValidatorTest {
  @Test
  fun `creates missing manifest for library module`(@TempDir tempDir: Path): Unit = runBlocking(Dispatchers.Default) {
    val jps = createLibraryProject(tempDir)
    val strategy = DeferredFileUpdater(tempDir)
    val model = testGenerationModel(pluginGraph {}, outputProvider = jps.outputProvider, fileUpdater = strategy)

    runValidationRule(LibraryModuleManifestValidator, model)

    val diff = strategy.getDiffs().single()
    assertThat(diff.changeType).isEqualTo(FileChangeType.CREATE)
    assertThat(diff.path).isEqualTo(manifestPath(tempDir))
    assertThat(diff.expectedContent).isEqualTo(expectedManifestContent())
    assertThat(diff.actualContent).isEmpty()
  }

  @Test
  fun `rewrites incorrect manifest content`(@TempDir tempDir: Path): Unit = runBlocking(Dispatchers.Default) {
    val jps = createLibraryProject(tempDir)
    val manifestPath = manifestPath(tempDir)
    Files.createDirectories(manifestPath.parent)
    Files.writeString(manifestPath, "Automatic-Module-Name: tls.channel\n")

    val strategy = DeferredFileUpdater(tempDir)
    val model = testGenerationModel(pluginGraph {}, outputProvider = jps.outputProvider, fileUpdater = strategy)

    runValidationRule(LibraryModuleManifestValidator, model)

    val diff = strategy.getDiffs().single()
    assertThat(diff.changeType).isEqualTo(FileChangeType.MODIFY)
    assertThat(diff.path).isEqualTo(manifestPath)
    assertThat(diff.actualContent).isEqualTo("Automatic-Module-Name: tls.channel\n")
    assertThat(diff.expectedContent).isEqualTo(expectedManifestContent())
  }

  @Test
  fun `ignores library module with correct manifest`(@TempDir tempDir: Path): Unit = runBlocking(Dispatchers.Default) {
    val jps = createLibraryProject(tempDir)
    val manifestPath = manifestPath(tempDir)
    Files.createDirectories(manifestPath.parent)
    Files.writeString(manifestPath, expectedManifestContent())

    val strategy = DeferredFileUpdater(tempDir)
    val model = testGenerationModel(pluginGraph {}, outputProvider = jps.outputProvider, fileUpdater = strategy)

    runValidationRule(LibraryModuleManifestValidator, model)

    assertThat(strategy.getDiffs()).isEmpty()
  }

  @Test
  fun `ignores non library modules`(@TempDir tempDir: Path): Unit = runBlocking(Dispatchers.Default) {
    val jps = jpsProject(tempDir) {
      module("intellij.regular.module") {
        resourceRoot()
      }
    }
    val strategy = DeferredFileUpdater(tempDir)
    val model = testGenerationModel(pluginGraph {}, outputProvider = jps.outputProvider, fileUpdater = strategy)

    runValidationRule(LibraryModuleManifestValidator, model)

    assertThat(strategy.getDiffs()).isEmpty()
  }

  @Test
  fun `update suppressions mode skips manifest writes`(@TempDir tempDir: Path): Unit = runBlocking(Dispatchers.Default) {
    val jps = createLibraryProject(tempDir)
    val strategy = DeferredFileUpdater(tempDir)
    val model = testGenerationModel(
      pluginGraph {},
      outputProvider = jps.outputProvider,
      fileUpdater = strategy,
      updateSuppressions = true,
    )

    runValidationRule(LibraryModuleManifestValidator, model)

    assertThat(strategy.getDiffs()).isEmpty()
  }

  @Test
  fun `default pipeline runs library module manifest validator`(@TempDir tempDir: Path): Unit = runBlocking(Dispatchers.Default) {
    val jps = createLibraryProject(tempDir)
    val pipeline = GenerationPipeline.default()
    val result = pipeline.execute(
      config = ModuleSetGenerationConfig(
        moduleSetSources = emptyMap(),
        discoveredProducts = emptyList(),
        projectRoot = tempDir,
        outputProvider = jps.outputProvider,
        projectLibraryToModuleMap = jps.outputProvider.getProjectLibraryToModuleMap(),
      ),
      commitChanges = false,
    )

    assertThat(result.errors).isEmpty()
    assertThat(result.diffs.map { it.path })
      .contains(manifestPath(tempDir))
  }
}

private const val LIBRARY_MODULE_NAME: String = "intellij.libraries.junit4"

private fun createLibraryProject(tempDir: Path) = jpsProject(tempDir) {
  module(LIBRARY_MODULE_NAME) {
    resourceRoot()
  }
}

private fun manifestPath(projectRoot: Path): Path {
  return projectRoot.resolve(LIBRARY_MODULE_NAME.replace('.', '/')).resolve("resources/META-INF/MANIFEST.mf")
}

private fun expectedManifestContent(): String {
  return "Automatic-Module-Name: $LIBRARY_MODULE_NAME\n"
}
