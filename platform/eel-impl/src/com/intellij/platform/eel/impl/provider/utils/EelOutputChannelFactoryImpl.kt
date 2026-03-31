// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.eel.impl.provider.utils

import com.intellij.platform.eel.provider.utils.EelOutputChannel
import com.intellij.platform.eel.provider.utils.EelOutputChannelFactory

internal class EelOutputChannelFactoryImpl : EelOutputChannelFactory {
  override fun create(prefersDirectBuffers: Boolean): EelOutputChannel = EelOutputChannelImpl(prefersDirectBuffers)
}
