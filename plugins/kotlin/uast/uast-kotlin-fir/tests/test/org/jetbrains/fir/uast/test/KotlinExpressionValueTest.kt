// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.fir.uast.test

class KotlinExpressionValueTest : AbstractKotlinExpressionValueTest() {
    fun testDelegate() {
        doCheck("Delegate.kt")
    }
}