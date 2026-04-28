// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.fir.uast.test

import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.uast.test.kotlin.TEST_KOTLIN_MODEL_PATH
import java.nio.file.Path

class KotlinUastTypesTest : AbstractFirUastTypesTest() {

    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K2

    override val testBasePath: Path = TEST_KOTLIN_MODEL_PATH

    fun testLocalDeclarations() = doCheck("LocalDeclarations.kt")

    fun testUnexpectedContainerException() = doCheck("UnexpectedContainerException.kt")

    fun testCycleInTypeParameters() = doCheck("CycleInTypeParameters.kt")

    fun testEa101715() = doCheck("ea101715.kt")

    fun testStringTemplate() = doCheck("StringTemplate.kt")

    fun testStringTemplateComplex() = doCheck("StringTemplateComplex.kt")

    fun testInferenceInsideUnresolvedConstructor() = doCheck("InferenceInsideUnresolvedConstructor.kt")

    fun testInnerNonFixedTypeVariable() = doCheck("InnerNonFixedTypeVariable.kt")

    fun testAnnotatedTypes() = doCheck("AnnotatedTypes.kt")
}
