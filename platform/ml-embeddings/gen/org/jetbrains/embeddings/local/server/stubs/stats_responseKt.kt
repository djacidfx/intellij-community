// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

//Generated by the protocol buffer compiler. DO NOT EDIT!
// source: embeddings.proto

package org.jetbrains.embeddings.local.server.stubs;

@kotlin.jvm.JvmSynthetic
public inline fun statsResponse(block: org.jetbrains.embeddings.local.server.stubs.stats_responseKt.Dsl.() -> kotlin.Unit): org.jetbrains.embeddings.local.server.stubs.Embeddings.stats_response =
  org.jetbrains.embeddings.local.server.stubs.stats_responseKt.Dsl._create(org.jetbrains.embeddings.local.server.stubs.Embeddings.stats_response.newBuilder()).apply { block() }._build()
public object stats_responseKt {
  @kotlin.OptIn(com.google.protobuf.kotlin.OnlyForUseByGeneratedProtoCode::class)
  @com.google.protobuf.kotlin.ProtoDslMarker
  public class Dsl private constructor(
    private val _builder: org.jetbrains.embeddings.local.server.stubs.Embeddings.stats_response.Builder
  ) {
    public companion object {
      @kotlin.jvm.JvmSynthetic
      @kotlin.PublishedApi
      internal fun _create(builder: org.jetbrains.embeddings.local.server.stubs.Embeddings.stats_response.Builder): Dsl = Dsl(builder)
    }

    @kotlin.jvm.JvmSynthetic
    @kotlin.PublishedApi
    internal fun _build(): org.jetbrains.embeddings.local.server.stubs.Embeddings.stats_response = _builder.build()

    /**
     * <code>int32 size = 1;</code>
     */
    public var size: kotlin.Int
      @JvmName("getSize")
      get() = _builder.getSize()
      @JvmName("setSize")
      set(value) {
        _builder.setSize(value)
      }
    /**
     * <code>int32 size = 1;</code>
     */
    public fun clearSize() {
      _builder.clearSize()
    }

    /**
     * <code>int64 bytes = 2;</code>
     */
    public var bytes: kotlin.Long
      @JvmName("getBytes")
      get() = _builder.getBytes()
      @JvmName("setBytes")
      set(value) {
        _builder.setBytes(value)
      }
    /**
     * <code>int64 bytes = 2;</code>
     */
    public fun clearBytes() {
      _builder.clearBytes()
    }
  }
}
public inline fun org.jetbrains.embeddings.local.server.stubs.Embeddings.stats_response.copy(block: org.jetbrains.embeddings.local.server.stubs.stats_responseKt.Dsl.() -> kotlin.Unit): org.jetbrains.embeddings.local.server.stubs.Embeddings.stats_response =
  org.jetbrains.embeddings.local.server.stubs.stats_responseKt.Dsl._create(this.toBuilder()).apply { block() }._build()
