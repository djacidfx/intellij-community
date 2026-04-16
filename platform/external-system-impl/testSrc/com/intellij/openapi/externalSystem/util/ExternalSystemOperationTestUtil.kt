// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("ExternalSystemOperationTestUtil")

package com.intellij.openapi.externalSystem.util

import com.intellij.openapi.project.Project
import com.intellij.testFramework.TestObservation
import org.jetbrains.annotations.ApiStatus.Obsolete
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

val DEFAULT_SYNC_TIMEOUT: Duration = 10.minutes

@JvmField
val DEFAULT_SYNC_TIMEOUT_MS: Long = DEFAULT_SYNC_TIMEOUT.inWholeMilliseconds

suspend fun awaitOpenProjectActivity(openProject: suspend () -> Project): Project =
  TestObservation.awaitOpenProjectActivity(DEFAULT_SYNC_TIMEOUT, openProject)

suspend fun <R> awaitProjectActivity(project: Project, action: suspend () -> R): R =
  TestObservation.awaitProjectActivity(project, DEFAULT_SYNC_TIMEOUT, action)

@Obsolete
fun waitForProjectActivity(project: Project, action: Runnable): Unit =
  TestObservation.waitForProjectActivity(project, DEFAULT_SYNC_TIMEOUT_MS, action)

@Obsolete
fun <R> waitForProjectActivity(project: Project, action: () -> R): R =
  TestObservation.waitForProjectActivity(project, DEFAULT_SYNC_TIMEOUT_MS, action)