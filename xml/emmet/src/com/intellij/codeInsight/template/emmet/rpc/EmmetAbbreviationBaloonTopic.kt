// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.emmet.rpc

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.impl.EditorId
import com.intellij.openapi.editor.impl.editorId
import com.intellij.platform.rpc.topics.ProjectRemoteTopic
import com.intellij.platform.rpc.topics.broadcast
import kotlinx.serialization.Serializable

@Serializable
data class ShowAbbreviationBaloonUiEvent(
  val editorId: EditorId,
  val historyKey: String,
)

object EmmetAbbreviationBaloonTopic {
  val TOPIC: ProjectRemoteTopic<ShowAbbreviationBaloonUiEvent> =
    ProjectRemoteTopic("emmet.showAbbreviationBaloon", ShowAbbreviationBaloonUiEvent.serializer())

  @JvmStatic
  fun invokeUi(editor: Editor, abbreviation: String) {
    val project = editor.project ?: return
    TOPIC.broadcast(project, ShowAbbreviationBaloonUiEvent(editor.editorId(), abbreviation))
  }
}
