// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.fir.uast.test

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementVisitor
import com.intellij.testFramework.assertEqualsToFile
import org.jetbrains.fir.uast.test.env.kotlin.AbstractFirUastTest
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import org.jetbrains.uast.test.kotlin.TEST_KOTLIN_MODEL_PATH
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.visitor.UastVisitor
import java.nio.file.Path

class KotlinUastNonVisitorConversionsTest : AbstractFirUastTest() {

    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K2

    override val testBasePath: Path = TEST_KOTLIN_MODEL_PATH

    override fun check(filePath: String, file: UFile) {
        val visitedElements = mutableSetOf<PsiElement>()
        file.accept(object : UastVisitor {
            override fun visitElement(node: UElement): Boolean {
                node.sourcePsi?.let {
                    visitedElements.add(it)
                }
                return false
            }
        })
        val missedText = StringBuilder()
        file.sourcePsi.accept(object : PsiRecursiveElementVisitor() {
            override fun visitElement(element: PsiElement) {
                if (!visitedElements.contains(element)) {
                    element.toUElement()?.let { uElement ->
                        missedText
                            .append(element.javaClass.canonicalName)
                            .append(": ")
                        generateSequence(uElement) { it.uastParent }.take(5).map { it.asLogString() }
                            .joinTo(missedText, " <- ")
                        missedText.appendLine()
                    }
                }
                super.visitElement(element)
            }
        })

        assertEqualsToFile("MissedElements", testBasePath.resolve("${getTestName(false)}.missed.txt").toFile(), missedText.toString())
    }

    fun testClassAnnotation() = doCheck("ClassAnnotation.kt")

    fun testLocalDeclarations() = doCheck("LocalDeclarations.kt")

    fun testComments() = doCheck("Comments.kt")

    fun testConstructors() = doCheck("Constructors.kt")

    fun testSimpleAnnotated() = doCheck("SimpleAnnotated.kt")

    fun testAnonymous() = doCheck("Anonymous.kt")

    fun testAnnotationParameters() = doCheck("AnnotationParameters.kt")

    fun testLambdas() = doCheck("Lambdas.kt")

    fun testSuperCalls() = doCheck("SuperCalls.kt")

    fun testPropertyInitializer() = doCheck("PropertyInitializer.kt")

    fun testEnumValuesConstructors() = doCheck("EnumValuesConstructors.kt")

    fun testNonTrivialIdentifiers() = doCheck("NonTrivialIdentifiers.kt")

    fun testBrokenDataClass() = doCheck("BrokenDataClass.kt")

    fun testBrokenGeneric() = doCheck("BrokenGeneric.kt")

    fun testTryCatch() = doCheck("TryCatch.kt")
}