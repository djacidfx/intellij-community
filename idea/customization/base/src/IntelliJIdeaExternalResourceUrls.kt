// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.idea.customization.base

import com.intellij.openapi.util.SystemInfoRt
import com.intellij.platform.ide.impl.customization.BaseJetBrainsExternalProductResourceUrls
import com.intellij.util.Url
import com.intellij.util.Urls

class IntelliJIdeaExternalResourceUrls : BaseJetBrainsExternalProductResourceUrls() {
  override val basePatchDownloadUrl: Url
    get() = Urls.newFromEncoded("https://download.jetbrains.com/idea/")

  override val productPageUrl: Url
    get() = baseWebSiteUrl.resolve("idea/")

  override val youtrackProjectId: String
    get() = "IDEA"

  override val shortProductNameUsedInForms: String
    get() = "IDEA"

  override val useInIdeGeneralFeedback: Boolean
    get() = true

  override val useInIdeEvaluationFeedback: Boolean
    get() = true

  override val youTubeChannelUrl: Url
    get() = Urls.newFromEncoded("https://www.youtube.com/user/intellijideavideo")

  override val keyboardShortcutsPdfUrl: Url
    get() {
      val suffix = if (SystemInfoRt.isMac) "_Mac" else ""
      return baseWebSiteUrl.resolve("idea/docs/IntelliJIDEA_ReferenceCard$suffix.pdf")
    }

  override val gettingStartedPageUrl: Url
    get() = baseWebSiteUrl.resolve("idea/resources/")

  override val baseWebHelpUrl: Url
    get() = baseWebSiteUrl.resolve("help/idea/")
}