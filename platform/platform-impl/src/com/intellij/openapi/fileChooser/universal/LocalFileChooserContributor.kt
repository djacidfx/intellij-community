// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileChooser.universal

import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.asEelPath
import java.nio.file.Path

internal class LocalFileChooserContributor() : UniversalFileChooserContributor {
  override val tabTitle: String = "Local"

  override fun getRoots(): List<Path> =
    getFilteredSystemRoots { path -> path.asEelPath().descriptor is LocalEelDescriptor }

}
