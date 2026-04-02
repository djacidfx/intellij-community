// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution

import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.target.LanguageRuntimeType
import com.intellij.execution.target.TargetEnvironmentConfiguration
import com.intellij.execution.target.TargetEnvironmentType
import com.intellij.execution.target.TargetEnvironmentWizard
import com.intellij.execution.target.getTargetType
import com.intellij.execution.target.local.LocalTargetType
import com.intellij.execution.ui.InvalidRunConfigurationIcon
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.util.NlsContexts
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon


@ApiStatus.Internal
sealed class Item(val displayName: @NlsContexts.Label String, open val icon: Icon?)

@ApiStatus.Internal
class Separator(displayName: @NlsContexts.Label String) : Item(displayName, null)

@ApiStatus.Internal
abstract class Target(displayName: @NlsContexts.Label String, icon: Icon, val targetName: String) : Item(displayName, icon)

@ApiStatus.Internal
class SavedTarget(private val config: TargetEnvironmentConfiguration) :
  Target(config.displayName, config.getTargetType().icon, config.displayName) {

  var validationInfo: ValidationInfo? = null
    private set

  init {
    revalidateConfiguration()
  }

  fun revalidateConfiguration() {
    try {
      config.validateConfiguration()
      validationInfo = null
    }
    catch (e: RuntimeConfigurationException) {
      @Suppress("HardCodedStringLiteral")
      validationInfo = ValidationInfo(e.localizedMessage)
    }
  }

  fun hasErrors(): Boolean {
    return this.validationInfo != null
  }

  override val icon: Icon?
    get() {
      val rawIcon = super.icon
      return if (rawIcon != null && hasErrors()) InvalidRunConfigurationIcon(rawIcon) else rawIcon
    }
}

/**
 * Represents the "local machine" target.
 */
@ApiStatus.Internal
class LocalTarget : Target(ExecutionBundle.message("local.machine"), AllIcons.Nodes.HomeFolder, LocalTargetType.LOCAL_TARGET_NAME)

@ApiStatus.Internal
class Type<T : TargetEnvironmentConfiguration>(private val type: TargetEnvironmentType<T>) :
  Item(ExecutionBundle.message("run.on.targets.label.new.target.of.type", type.displayName), type.icon) {

  fun createWizard(project: Project, languageRuntime: LanguageRuntimeType<*>?): TargetEnvironmentWizard? {
    return TargetEnvironmentWizard.createWizard(project, type, languageRuntime)
  }
}
