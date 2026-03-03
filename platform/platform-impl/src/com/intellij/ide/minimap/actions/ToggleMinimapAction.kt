// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.minimap.actions

import com.intellij.ide.minimap.settings.MinimapSettings
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.RightAlignedToolbarAction
import com.intellij.openapi.actionSystem.remoting.ActionRemoteBehaviorSpecification
import com.intellij.openapi.project.DumbAwareToggleAction

class ToggleMinimapAction : DumbAwareToggleAction(), RightAlignedToolbarAction, ActionRemoteBehaviorSpecification.Frontend {
  override fun isSelected(e: AnActionEvent): Boolean = MinimapSettings.getInstance().state.enabled
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
  override fun setSelected(e: AnActionEvent, state: Boolean) {
    val settings = MinimapSettings.getInstance()
    settings.state.enabled = state
    settings.settingsChangeCallback.notify(MinimapSettings.SettingsChangeType.WithUiRebuild)
  }
}