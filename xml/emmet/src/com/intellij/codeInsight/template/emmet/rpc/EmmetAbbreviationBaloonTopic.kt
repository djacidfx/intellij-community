// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.emmet.rpc

import com.intellij.openapi.editor.impl.EditorId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts.Tooltip
import com.intellij.platform.rpc.topics.ProjectRemoteTopic
import com.intellij.platform.rpc.topics.broadcast
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class ShowAbbreviationBaloonUiEvent(
  val transactionId: Int,
  val editorId: EditorId,
  val historyKey: String,
  val lastAbbreviationKey: String,
  val linkText: @Tooltip String?,
  val linkUrl: String?,
  val description: @Tooltip String,
)

object EmmetAbbreviationBaloonTopic {
  val TOPIC: ProjectRemoteTopic<ShowAbbreviationBaloonUiEvent> =
    ProjectRemoteTopic("emmet.showAbbreviationBaloon", ShowAbbreviationBaloonUiEvent.serializer())

  private val transactionIdCounter: AtomicInteger = AtomicInteger(0)

  @JvmStatic
  fun nextTransactionId(): Int = transactionIdCounter.getAndIncrement()

  @JvmStatic
  fun invokeUi(project: Project, event: ShowAbbreviationBaloonUiEvent): Unit = TOPIC.broadcast(project, event)
}
