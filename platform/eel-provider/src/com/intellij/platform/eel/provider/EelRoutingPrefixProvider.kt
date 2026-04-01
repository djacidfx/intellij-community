// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.eel.provider

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.annotations.MultiRoutingFileSystemPath
import org.jetbrains.annotations.ApiStatus

/**
 * Provides local path prefixes that route to the environment described by [EelDescriptor].
 *
 * Makes sense only on Windows, because on POSIX there is only the root `/`.
 * These are additional elements returned by `FileSystems.getDefault().getRootDirectories()`.
 */
@ApiStatus.Internal
interface EelRoutingPrefixProvider {
  companion object {
    val EP_NAME: ExtensionPointName<EelRoutingPrefixProvider> = ExtensionPointName("com.intellij.eelRoutingPrefixProvider")
  }

  fun getRoutingPrefixes(eelDescriptor: EelDescriptor): Collection<@MultiRoutingFileSystemPath String>?
}
