// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.ijent.spi

import com.intellij.platform.eel.SafeDeferred
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope

data class IjentTcpSessionMediator(
  override val ijentProcessScope: CoroutineScope,
  override val processExit: SafeDeferred<Unit>,
  val remotePid: CompletableDeferred<Long> = CompletableDeferred(),
) : IjentSessionMediator