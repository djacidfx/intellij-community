// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.tools.ide.starter.build.server.datagrip

import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.di.initDevBuildServerDiBinding
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.IdeInfoType
import org.junit.platform.launcher.TestExecutionListener
import org.kodein.di.direct
import org.kodein.di.instance

/**
 * DataGrip [IdeInfo] resolved from DI.
 *
 * Tests that need DataGrip should depend on this module
 * (`intellij.tools.ide.starter.build.server.datagrip`).
 */
val IdeInfo.Companion.DataGrip: IdeInfo
  get() {
    DataGripDevBuildServerListener.init()
    return di.direct.instance<IdeInfo>(tag = IdeInfoType.DATAGRIP)
  }

internal val DefaultDataGrip = IdeInfo(
  productCode = "DB",
  platformPrefix = "DataGrip",
  executableFileName = "datagrip",
  fullName = "DataGrip"
)

/**
 * Registers DataGrip [IdeInfo] in DI and initializes Dev Build Server support.
 */
class DataGripDevBuildServerListener : TestExecutionListener {
  companion object {
    init {
      init()
    }

    fun init() {
      initDevBuildServerDiBinding(IdeInfoType.DATAGRIP, DefaultDataGrip)
    }
  }
}
