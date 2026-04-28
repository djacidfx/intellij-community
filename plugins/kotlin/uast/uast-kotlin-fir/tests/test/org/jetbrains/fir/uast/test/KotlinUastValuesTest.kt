// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.fir.uast.test

import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.uast.test.kotlin.TEST_KOTLIN_MODEL_PATH
import java.nio.file.Path

class KotlinUastValuesTest : AbstractFirUastValuesTest() {

    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K2

    override val testBasePath: Path = TEST_KOTLIN_MODEL_PATH

    fun testAssertion() = doCheck("Assertion.kt")

    fun testDelegate() = doCheck("Delegate.kt")

    fun testIn() = doCheck("In.kt")

    fun testLocalDeclarations() = doCheck("LocalDeclarations.kt")

    fun testSimple() = doCheck("Simple.kt")

    fun testStringTemplateComplex() = doCheck("StringTemplateComplex.kt")
}