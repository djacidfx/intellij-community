// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("WslIjentTestUtil")

package com.intellij.ijent.testFramework.wsl

import com.intellij.execution.wsl.WSLDistribution
import com.intellij.openapi.Disposable
import kotlinx.coroutines.CoroutineScope

@Deprecated("To be removed")
fun replaceProductionWslIjentManager(disposable: Disposable): Unit = Unit

@Deprecated("To be removed")
fun replaceProductionWslIjentManager(newServiceScope: CoroutineScope): Unit = Unit

@Deprecated("To be removed")
suspend fun replaceWslServicesAndRunWslEelInitialization(newServiceScope: CoroutineScope, wsl: WSLDistribution): Unit = Unit