// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.fir.uast.test

import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.uast.test.kotlin.TEST_KOTLIN_MODEL_PATH
import java.nio.file.Path

class KotlinUastIdentifiersTest : AbstractFirUastIdentifiersTest() {

    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K2

    override val testBasePath: Path = TEST_KOTLIN_MODEL_PATH

    fun testLocalDeclarations() = doCheck("LocalDeclarations.kt")

    fun testComments() = doCheck("Comments.kt")

    fun testConstructors() = doCheck("Constructors.kt")

    fun testSimpleAnnotated() = doCheck("SimpleAnnotated.kt")

    fun testAnonymous() = doCheck("Anonymous.kt")

    fun testLambdas() = doCheck("Lambdas.kt")

    fun testSuperCalls() = doCheck("SuperCalls.kt")

    fun testPropertyInitializer() = doCheck("PropertyInitializer.kt")

    fun testEnumValuesConstructors() = doCheck("EnumValuesConstructors.kt")

    fun testNonTrivialIdentifiers() = doCheck("NonTrivialIdentifiers.kt")

    fun testBrokenDataClass() = doCheck("BrokenDataClass.kt")

    fun testBrokenGeneric() = doCheck("BrokenGeneric.kt")
}