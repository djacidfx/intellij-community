/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.diagnostic.hprof.util

import com.intellij.diagnostic.hprof.parser.HProfEventBasedParser
import com.intellij.util.lang.ByteBufferCleaner
import org.jetbrains.annotations.ApiStatus
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.InvalidMarkException
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

@ApiStatus.Internal
class HProfReadBufferSlidingWindow private constructor(
  private val channel: FileChannel,
  private val parser: HProfEventBasedParser,
  private val viewOffset: Long,
  val size: Long,
  private val windowSize: Long = DEFAULT_WINDOW_SIZE,
) : AbstractHProfNavigatorReadBuffer(parser) {
  constructor(channel: FileChannel, parser: HProfEventBasedParser) : this(channel, parser, 0, channel.size())

  private var bufferOffset = 0L
  private var buffer = createInitialBuffer()
  private var closed = false
  private var mark = -1L

  override fun close() {
    if (closed) {
      return
    }

    closed = true
    if (buffer.isDirect) {
      ByteBufferCleaner.unmapBuffer(buffer)
    }
  }

  override fun position(newPosition: Long) {
    require(newPosition in 0..size) { "Position $newPosition is out of bounds for size $size" }

    if (newPosition == size) {
      positionAtEnd()
      return
    }

    if (newPosition >= bufferOffset && newPosition <= bufferOffset + buffer.limit()) {
      buffer.position((newPosition - bufferOffset).toInt())
    }
    else {
      remapBuffer(newPosition)
    }
  }

  override fun isEof(): Boolean {
    return !hasRemaining()
  }

  override fun position(): Long {
    return bufferOffset + buffer.position()
  }

  override fun get(bytes: ByteArray) {
    if (bytes.isEmpty()) {
      return
    }

    if (bytes.size <= buffer.remaining()) {
      buffer.get(bytes)
      return
    }

    var destinationOffset = 0
    while (destinationOffset < bytes.size) {
      if (!hasRemaining()) {
        throw BufferUnderflowException()
      }

      if (buffer.remaining() == 0) {
        remapBuffer(position())
      }

      val bytesToFetch = min(bytes.size - destinationOffset, buffer.remaining())
      buffer.get(bytes, destinationOffset, bytesToFetch)
      destinationOffset += bytesToFetch
    }
  }

  override fun getByteBuffer(size: Long): HProfReadBufferSlidingWindow {
    require(size >= 0) { "Buffer size must be non-negative: $size" }
    val startOffset = position()
    skip(size)
    return HProfReadBufferSlidingWindow(channel, parser, viewOffset + startOffset, size, windowSize)
  }

  override fun get(): Byte {
    ensureRemaining(Byte.SIZE_BYTES)
    return buffer.get()
  }

  override fun getShort(): Short {
    ensureRemaining(Short.SIZE_BYTES)
    return buffer.short
  }

  override fun getInt(): Int {
    ensureRemaining(Int.SIZE_BYTES)
    return buffer.int
  }

  override fun getLong(): Long {
    ensureRemaining(Long.SIZE_BYTES)
    return buffer.long
  }

  fun remaining(): Long = size - position()

  fun hasRemaining(): Boolean = remaining() > 0

  fun mark() {
    mark = position()
  }

  fun reset() {
    if (mark < 0) {
      throw InvalidMarkException()
    }
    position(mark)
  }

  fun getChar(): Char {
    ensureRemaining(Char.SIZE_BYTES)
    return buffer.char
  }

  fun getFloat(): Float {
    ensureRemaining(Float.SIZE_BYTES)
    return buffer.float
  }

  fun getDouble(): Double {
    ensureRemaining(Double.SIZE_BYTES)
    return buffer.double
  }

  fun get(index: Int): Byte = readAt(index.toLong()) { get() }

  fun getShort(index: Int): Short = readAt(index.toLong()) { getShort() }

  fun getChar(index: Int): Char = readAt(index.toLong()) { getChar() }

  fun getInt(index: Int): Int = readAt(index.toLong()) { getInt() }

  fun getFloat(index: Int): Float = readAt(index.toLong()) { getFloat() }

  fun getDouble(index: Int): Double = readAt(index.toLong()) { getDouble() }

  fun getLong(index: Int): Long = readAt(index.toLong()) { getLong() }

  internal fun currentWindow(): ByteBuffer {
    if (!hasRemaining()) {
      return EMPTY_BUFFER
    }
    return buffer.slice().asReadOnlyBuffer()
  }

  private fun ensureRemaining(byteCount: Int) {
    if (remaining() < byteCount) {
      throw BufferUnderflowException()
    }

    if (buffer.remaining() < byteCount) {
      remapBuffer(position())
    }
  }

  private inline fun <T> readAt(index: Long, reader: HProfReadBufferSlidingWindow.() -> T): T {
    val oldPosition = position()
    position(index)
    return try {
      reader()
    }
    finally {
      position(oldPosition)
    }
  }

  private fun createInitialBuffer(): ByteBuffer {
    if (size == 0L) {
      return EMPTY_BUFFER
    }

    return channel.map(FileChannel.MapMode.READ_ONLY, viewOffset, min(windowSize, size))
  }

  private fun positionAtEnd() {
    if (size == 0L) {
      buffer.position(0)
      return
    }

    val lastWindowOffset = max(0L, size - min(windowSize, size))
    if (bufferOffset != lastWindowOffset) {
      remapBuffer(lastWindowOffset)
    }
    buffer.position((size - bufferOffset).toInt())
  }

  private fun remapBuffer(newPosition: Long) {
    val oldBuffer = buffer
    val bytesToMap = min(windowSize, size - newPosition)
    buffer = if (bytesToMap == 0L) EMPTY_BUFFER else channel.map(FileChannel.MapMode.READ_ONLY, viewOffset + newPosition, bytesToMap)
    bufferOffset = newPosition

    if (oldBuffer.isDirect) {
      ByteBufferCleaner.unmapBuffer(oldBuffer)
    }
  }

  companion object {
    private const val DEFAULT_WINDOW_SIZE = 10_000_000L
    private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0).asReadOnlyBuffer()
  }
}
