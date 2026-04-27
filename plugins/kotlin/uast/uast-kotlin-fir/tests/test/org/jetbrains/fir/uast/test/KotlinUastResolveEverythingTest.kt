// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.fir.uast.test

import org.jetbrains.fir.uast.test.env.kotlin.AbstractFirUastTest
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.uast.UFile
import org.jetbrains.uast.test.common.kotlin.resolvableWithTargets
import org.jetbrains.uast.test.env.kotlin.assertEqualsToFile
import org.jetbrains.uast.test.kotlin.TEST_KOTLIN_MODEL_PATH
import java.nio.file.Path

class KotlinUastResolveEverythingTest : AbstractFirUastTest() {
    override val testBasePath: Path = TEST_KOTLIN_MODEL_PATH

    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K2

    override fun check(filePath: String, file: UFile) {
        val expected = Path.of(filePath.removeSuffix(".kt") + ".resolved.txt").toFile()
        assertEqualsToFile("resolved", expected, file.resolvableWithTargets())
    }

    fun testClassAnnotation() = doCheck("ClassAnnotation.kt")

    fun testLocalDeclarations() = doCheck("LocalDeclarations.kt")

    fun testConstructors() = doCheck("Constructors.kt")

    fun testSimpleAnnotated() = doCheck("SimpleAnnotated.kt")

    fun testAnonymous() = doCheck("Anonymous.kt")

    fun testTypeReferences() = doCheck("TypeReferences.kt")

    fun testImports() = doCheck("Imports.kt")

    fun testReifiedResolve() = doCheck("ReifiedResolve.kt")

    fun testResolve() = doCheck("Resolve.kt")

    fun testPropertyReferences() = doCheck("PropertyReferences.kt")

    fun testTypeAliasConstructorReference() = doCheck("TypeAliasConstructorReference.kt")

    fun testComments() = doCheck("Comments.kt")
}