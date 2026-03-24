// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileChooser.universal

import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import java.nio.file.FileSystems
import java.nio.file.Path

@ApiStatus.Internal
interface UniversalFileChooserContributor {
  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<UniversalFileChooserContributor> = ExtensionPointName("com.intellij.universalFileChooserContributor")

  }

  @get:Nls(capitalization = Nls.Capitalization.Title)
  val tabTitle: String

  fun getRoots(): List<Path>

}

fun getFilteredSystemRoots(predicate: (Path) -> Boolean): List<Path> {
  return FileSystems.getDefault().getRootDirectories().filter(predicate)
}