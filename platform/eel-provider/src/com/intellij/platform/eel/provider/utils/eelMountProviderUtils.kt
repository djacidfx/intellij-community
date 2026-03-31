// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.eel.provider.utils

import com.intellij.platform.eel.provider.EelMountRoot
import com.intellij.platform.eel.provider.toEelApi
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
suspend fun EelMountRoot.canReadPermissionsDirectly(options: EelMountRoot.DirectAccessOptions): Boolean {
  return canReadPermissionsDirectly(targetRoot.descriptor.toEelApi().fs, localRoot.descriptor.toEelApi().fs, options)
}
