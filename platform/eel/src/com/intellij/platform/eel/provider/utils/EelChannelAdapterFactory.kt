// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.eel.provider.utils

import com.intellij.platform.eel.channels.EelReceiveChannel
import com.intellij.platform.eel.channels.EelSendChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import org.jetbrains.annotations.ApiStatus
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.charset.Charset
import java.util.ServiceLoader
import kotlin.coroutines.CoroutineContext

/**
 * SPI for creating channel adapters between EEL channels and Java I/O. Loaded via [ServiceLoader].
 */
@ApiStatus.Internal
interface EelChannelAdapterFactory {
  fun wrapReadableChannel(channel: ReadableByteChannel, available: () -> Int): EelReceiveChannel
  fun wrapWritableChannel(channel: WritableByteChannel, flushable: Any?): EelSendChannel
  fun wrapAsInputStream(channel: EelReceiveChannel, blockingContext: CoroutineContext): InputStream
  fun wrapAsOutputStream(channel: EelSendChannel, blockingContext: CoroutineContext): OutputStream
  fun CoroutineScope.consumeReceiveChannelAsKotlin(channel: EelReceiveChannel): ReceiveChannel<ByteBuffer>
  fun lines(channel: EelReceiveChannel, charset: Charset): Flow<String>
  fun socketAsReceiveChannel(socket: Socket): EelReceiveChannel
  fun socketAsSendChannel(socket: Socket): EelSendChannel

  companion object {
    val instance: EelChannelAdapterFactory by lazy {
      ServiceLoader.load(EelChannelAdapterFactory::class.java).single()
    }
  }
}
