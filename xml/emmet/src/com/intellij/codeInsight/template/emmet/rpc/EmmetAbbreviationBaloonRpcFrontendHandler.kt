// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.emmet.rpc

import com.intellij.openapi.application.invokeLater
import kotlinx.coroutines.launch

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
}
