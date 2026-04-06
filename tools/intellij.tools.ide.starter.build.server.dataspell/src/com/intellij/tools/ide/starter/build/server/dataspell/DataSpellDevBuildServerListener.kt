// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.tools.ide.starter.build.server.dataspell

import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.IdeInfoType
import com.intellij.ide.starter.models.IdeProductInit
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * DataSpell [IdeInfo] resolved from DI.
 *
 * Tests that need DataSpell should depend on this module
 * (`intellij.tools.ide.starter.build.server.dataspell`).
 */
val IdeInfo.Companion.DataSpell: IdeInfo
  get() {
    return di.direct.instance<IdeInfo>(tag = IdeInfoType.DATASPELL)
  }

internal val DefaultDataSpell = IdeInfo(
  productCode = "DS",
  platformPrefix = "DataSpell",
  executableFileName = "dataspell",
  fullName = "DataSpell"
)

/**
 * Registers DataSpell [IdeInfo] in DI and initializes Dev Build Server support.
 */
class DataSpellDevBuildServerListener : IdeProductInit {
  override val ideInfoType: IdeInfoType = IdeInfoType.DATASPELL
  override val ideInfo: IdeInfo = DefaultDataSpell
}
