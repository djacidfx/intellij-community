// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.eel.impl.provider.utils

import com.intellij.platform.eel.channels.EelReceiveChannel
import com.intellij.platform.eel.channels.EelSendChannel
import com.intellij.platform.eel.provider.utils.EelChannelAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import java.io.Flushable
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.charset.Charset
import kotlin.coroutines.CoroutineContext

internal class EelChannelAdapterFactoryImpl : EelChannelAdapterFactory {
  override fun wrapReadableChannel(channel: ReadableByteChannel, available: () -> Int): EelReceiveChannel =
    NioReadToEelAdapter(channel, available)

  override fun wrapWritableChannel(channel: WritableByteChannel, flushable: Any?): EelSendChannel =
    NioWriteToEelAdapter(channel, flushable as? Flushable)

  override fun wrapAsInputStream(channel: EelReceiveChannel, blockingContext: CoroutineContext): InputStream =
    InputStreamAdapterImpl(channel, blockingContext)

  override fun wrapAsOutputStream(channel: EelSendChannel, blockingContext: CoroutineContext): OutputStream =
    OutputStreamAdapterImpl(channel, blockingContext)

  override fun CoroutineScope.consumeReceiveChannelAsKotlin(channel: EelReceiveChannel): ReceiveChannel<ByteBuffer> =
    consumeReceiveChannelAsKotlinImpl(channel)

  override fun lines(channel: EelReceiveChannel, charset: Charset): Flow<String> =
    channel.linesImpl(charset)

  override fun socketAsReceiveChannel(socket: Socket): EelReceiveChannel =
    socket.consumeAsEelChannelImpl()

  override fun socketAsSendChannel(socket: Socket): EelSendChannel =
    socket.asEelChannelImpl()
}
