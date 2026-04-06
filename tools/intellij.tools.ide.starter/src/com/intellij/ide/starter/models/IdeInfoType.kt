// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.starter.models

/**
 * Enum representing all supported IDE product types.
 * Used as DI tag for [IdeInfo] bindings.
 *
 * Each IDE-specific module (e.g., `intellij.tools.ide.starter.build.server.goland`)
 * registers its [IdeInfo] in DI using the corresponding [IdeInfoType] as a tag.
 */
enum class IdeInfoType(
  val productCode: String,
) {
  GOLAND("GO"),
  IDEA_ULTIMATE("IU"),
  IDEA_COMMUNITY("IC"),
  ANDROID_STUDIO("AI"),
  WEBSTORM("WS"),
  PHPSTORM("PS"),
  DATAGRIP("DB"),
  RUBYMINE("RM"),
  PYCHARM("PY"),
  CLION("CL"),
  DATASPELL("DS"),
  PYCHARM_COMMUNITY("PC"),
  AQUA("QA"),
  RUSTROVER("RR"),
  RIDER("RD"),
  GATEWAY("GW");

  companion object {
    fun fromProductCode(productCode: String): IdeInfoType? =
      entries.find { it.productCode == productCode }
  }
}


/** Interface for IDE product initialization discovered via [java.util.ServiceLoader]. */
interface IdeProductInit {
  val ideInfoType: IdeInfoType
  val ideInfo: IdeInfo
}
