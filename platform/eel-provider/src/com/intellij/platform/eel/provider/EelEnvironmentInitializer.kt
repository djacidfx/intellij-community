// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.eel.provider

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.EelMachine
import com.intellij.platform.eel.EelUnavailableException
import com.intellij.platform.eel.ThrowsChecked
import com.intellij.platform.eel.annotations.MultiRoutingFileSystemPath
import com.intellij.platform.util.coroutines.mapNotNullConcurrent
import kotlinx.coroutines.CancellationException
import org.jetbrains.annotations.ApiStatus

/**
 * Initializes the execution environment for a given path during project opening.
 *
 * This function runs **early**, so implementors need to be careful with performance.
 * This function is called for every opening project,
 * so the implementation is expected to exit quickly if it decides that it is not responsible for the path.
 */
@ApiStatus.Internal
interface EelEnvironmentInitializer {
  companion object {
    val EP_NAME: ExtensionPointName<EelEnvironmentInitializer> = ExtensionPointName("com.intellij.eelEnvironmentInitializer")
  }

  @ThrowsChecked(EelUnavailableException::class)
  suspend fun tryInitialize(path: @MultiRoutingFileSystemPath String): EelMachine?
}

@ApiStatus.Internal
object EelInitialization {
  private val logger = logger<EelInitialization>()

  @ThrowsChecked(EelUnavailableException::class)
  suspend fun runEelInitialization(path: String): EelMachine {
    val initializers = EelEnvironmentInitializer.EP_NAME.extensionList
    val machines = initializers.mapNotNullConcurrent { initializer ->
      try {
        initializer.tryInitialize(path)
      }
      catch (e: CancellationException) {
        throw e
      }
      catch (e: EelUnavailableException) {
        throw e
      }
      catch (e: Throwable) {
        logger.error(e)
        null
      }
    }

    if (machines.isEmpty()) {
      logger.debug("No EEL machines found for path: $path")
      return LocalEelMachine
    }

    if (machines.size > 1) {
      logger.error("Several EEL machines $machines found for path: $path")
    }

    return machines.first()
  }

  @ThrowsChecked(EelUnavailableException::class)
  suspend fun runEelInitialization(project: Project) {
    if (project.isDefault) {
      return
    }

    val projectFile = project.projectFilePath
    check(projectFile != null) { "Impossible: project is not default, but it does not have project file" }

    val machine = runEelInitialization(projectFile)

    project.setEelMachine(machine)
  }
}
