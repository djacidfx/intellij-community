// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.emmet.rpc

import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.ui.popup.Balloon
import com.intellij.ui.LightColors
import com.intellij.ui.TextFieldWithHistory
import kotlinx.coroutines.launch
import java.util.function.Consumer

/**
 * This is a proxy between the legacy code and the rpc approach
 */
object EmmetAbbreviationBaloonRpcFrontendHandler {
  @JvmStatic
  fun enter(showEvent: ShowAbbreviationBaloonUiEvent, abbreviation: String, callback: Runnable) {
    showEvent.project()?.let { project ->
      EmmetFrontendRpcService.scope(project).launch {
        EmmetAbbreviationBaloonRpc.instance().enter(showEvent.transactionId, showEvent.editorId, abbreviation)
        invokeLater {
          callback.run()
        }
      }
    }
  }

  @JvmStatic
  fun validateTemplateKey(
    showEvent: ShowAbbreviationBaloonUiEvent,
    field: TextFieldWithHistory,
    balloon: Balloon?,
    handler: Consumer<Boolean>,
  ) {
    val abbreviation = field.text
    showEvent.project()?.let { project ->
      EmmetFrontendRpcService.scope(project).launch {
        val isCorrect = EmmetAbbreviationBaloonRpc.instance().isValidTemplateKey(
          showEvent.transactionId, showEvent.editorId, abbreviation)

        invokeLater {
          if (!abbreviation.equals(field.text)) {
            // text changed while we were checking it
            validateTemplateKey(showEvent, field, balloon, handler)
            return@invokeLater
          }
          field.textEditor.setBackground(if (isCorrect) LightColors.SLIGHTLY_GREEN else LightColors.RED)
          if (balloon != null && !balloon.isDisposed()) {
            balloon.revalidate()
          }
          handler.accept(isCorrect)
        }
      }
    }
  }

  @JvmStatic
  fun cancel(showEvent: ShowAbbreviationBaloonUiEvent, callback: Runnable) {
    showEvent.project()?.let { project ->
      EmmetFrontendRpcService.scope(project).launch {
        invokeLater {
          callback.run()
        }
        EmmetAbbreviationBaloonRpc.instance().cancel(showEvent.transactionId, showEvent.editorId)
      }
    }
  }

  /**
   * This method probably could be removed, but it is more about the business logic and i don't want to change it now
   */
  @JvmStatic
  fun isValid(showEvent: ShowAbbreviationBaloonUiEvent, handler: Consumer<Boolean>) {
    showEvent.project()?.let { project ->
      EmmetFrontendRpcService.scope(project).launch {
        val isvalid = EmmetAbbreviationBaloonRpc.instance().isValid(showEvent.transactionId, showEvent.editorId)
        invokeLater {
          handler.accept(isvalid)
        }
      }
    }
  }
}
