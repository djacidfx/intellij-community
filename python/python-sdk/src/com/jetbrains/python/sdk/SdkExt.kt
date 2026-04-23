// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk

import com.intellij.execution.target.TargetBasedSdkAdditionalData
import com.intellij.execution.target.TargetEnvironmentConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.Sdk
import com.jetbrains.python.sdk.flavors.PythonSdkFlavor
import com.jetbrains.python.sdk.impl.buildPresentationInfo
import com.jetbrains.python.sdk.legacy.PythonSdkUtil.isPythonSdk
import com.jetbrains.python.target.PyTargetAwareAdditionalData
import org.jetbrains.annotations.ApiStatus.Internal

/**
 * Asserts that this SDK has [PythonSdkType].
 *
 * @throws IllegalArgumentException if the SDK type is not a Python SDK.
 */
@Internal
fun Sdk.requirePythonSdk() {
  require(isPythonSdk(this, true)) { "Can't be called only for PythonSdkType and not for $sdkType" }
}

/**
 * Associates this SDK with the given [module] by storing the module's base directory path
 * in [PythonSdkAdditionalData.associatedModulePath] and committing the change.
 *
 * Non-suspend version that schedules the write action on EDT via [runInEdt].
 * Use when calling from quick-fixes or other contexts where `suspend` is not available.
 *
 * @see setAssociationToModule for the suspend version
 */
@Internal
fun Sdk.setAssociationToModuleAsync(module: Module) {
  requirePythonSdk()

  val path = module.baseDir?.path
  assert(path != null) { "Module $module has not paths, and can't be associated" }

  val data = getOrCreateAdditionalData()
    .also {
      it.associatedModulePath = path
    }

  val modificator = sdkModificator
  modificator.sdkAdditionalData = data

  runInEdt {
    ApplicationManager.getApplication().runWriteAction {
      modificator.commitChanges()
    }
  }
}

/**
 * Associates this SDK with the given [module] by storing the module's base directory path
 * in [PythonSdkAdditionalData.associatedModulePath] and committing the change.
 *
 * @see setAssociationToModuleAsync for the non-suspend version
 */
@Internal
suspend fun Sdk.setAssociationToModule(module: Module) {
  requirePythonSdk()

  val path = module.baseDir?.path
  assert(path != null) { "Module $module has not paths, and can't be associated" }
  setAssociationToPath(path)
}

/**
 * Sets the [PythonSdkAdditionalData.associatedModulePath] to [path] and commits the change.
 *
 * Pass `null` to clear the association.
 */
@Internal
suspend fun Sdk.setAssociationToPath(path: String?) {
  requirePythonSdk()

  val data = getOrCreateAdditionalData()
    .also {
      it.associatedModulePath = path
    }

  val modificator = sdkModificator
  modificator.sdkAdditionalData = data

  writeAction {
    modificator.commitChanges()
  }
}


@Internal
fun Sdk.isRunAsRootViaSudo(): Boolean {
  val data = getSdkAdditionalData()
  return data is PyTargetAwareAdditionalData && data.isRunAsRootViaSudo()
}

/**
 * Returns whether this [Sdk] seems valid or not.
 *
 * The actual check logic is located in [PythonSdkFlavor.sdkSeemsValid] and its overrides. In general, the method check whether the path to
 * the Python binary stored in this [Sdk] exists and the corresponding file can be executed. This check can be performed both locally and
 * on a target. The latter case takes place when [PythonSdkAdditionalData] of this [Sdk] implements [PyTargetAwareAdditionalData] and the
 * corresponding target provides file system operations (see [com.jetbrains.python.pathValidation.ValidationRequest]).
 *
 *
 * @see PythonSdkFlavor.sdkSeemsValid
 */
val Sdk.isSdkSeemsValid: Boolean
  get() {
    if (!isPythonSdk(this, true)) return false
    if (this.sdkAdditionalData == PyInvalidSdk) {
      return false
    }

    val pythonSdkAdditionalData = getOrCreateAdditionalData()
    return pythonSdkAdditionalData.flavorAndData.sdkSeemsValid(this, targetEnvConfiguration)
  }

@Internal
fun Sdk?.isTargetBased(): Boolean = this != null && targetEnvConfiguration != null

/**
 *  Additional data if sdk is target-based
 */
@get:Internal
val Sdk.targetAdditionalData: PyTargetAwareAdditionalData?
  get():PyTargetAwareAdditionalData? = sdkAdditionalData as? PyTargetAwareAdditionalData

/**
 * Returns target environment if configuration is target api based
 */

@get:Internal
val Sdk.targetEnvConfiguration: TargetEnvironmentConfiguration?
  get():TargetEnvironmentConfiguration? = (sdkAdditionalData as? TargetBasedSdkAdditionalData)?.targetEnvironmentConfiguration


@Internal
fun Sdk.pyInterpreterPresentation(customName: String? = null): PythonInterpreterPresentation = buildPresentationInfo(customName)