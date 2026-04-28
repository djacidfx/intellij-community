// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.fir.uast.test

import org.jetbrains.fir.uast.test.env.kotlin.AbstractFirUastTest
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UFile
import org.jetbrains.uast.test.kotlin.AbstractKotlinRenderLogTest
import org.jetbrains.uast.test.kotlin.TEST_KOTLIN_MODEL_PATH
import java.nio.file.Path

class SimpleKotlinRenderLogTest : AbstractFirUastTest(), AbstractKotlinRenderLogTest {
    override fun checkLeak(node: UElement) {
    }

    override val testBasePath: Path = TEST_KOTLIN_MODEL_PATH

    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K2

    override fun check(fileName: String, file: UFile) {
        super<AbstractKotlinRenderLogTest>.check(getTestName(/* lowercaseFirstLetter = */ false), file)
    }

    fun testLocalDeclarations() = doCheck("LocalDeclarations.kt")

    fun testSimple() = doCheck("Simple.kt")

    fun testWhenIs() = doCheck("WhenIs.kt")

    fun testDefaultImpls() = doCheck("DefaultImpls.kt")

    fun testBitwise() = doCheck("Bitwise.kt")

    fun testElvis() = doCheck("Elvis.kt")

    fun testPropertyAccessors() = doCheck("PropertyAccessors.kt")

    fun testPropertyInitializer() = doCheck("PropertyInitializer.kt")

    fun testPropertyInitializerWithoutSetter() = doCheck("PropertyInitializerWithoutSetter.kt")

    fun testAnnotationParameters() = doCheck("AnnotationParameters.kt")

    fun testEnumValueMembers() = doCheck("EnumValueMembers.kt")

    fun testEnumValuesConstructors() = doCheck("EnumValuesConstructors.kt")

    fun testStringTemplate() = doCheck("StringTemplate.kt")

    fun testStringTemplateComplex() = doCheck("StringTemplateComplex.kt")

    fun testStringTemplateComplexForUInjectionHost() = doCheck("StringTemplateComplexForUInjectionHost.kt")

    fun testQualifiedConstructorCall() = doCheck("QualifiedConstructorCall.kt")

    fun testLocalVariableWithAnnotation() = doCheck("LocalVariableWithAnnotation.kt")

    fun testPropertyWithAnnotation() = doCheck("PropertyWithAnnotation.kt")

    fun testIfStatement() = doCheck("IfStatement.kt")

    fun testInnerClasses() = doCheck("InnerClasses.kt")

    // KTIJ-38566
    fun _testSimpleScript() = doCheck("SimpleScript.kt") { testName, file -> check(testName, file, false) }

    fun testDestructuringDeclaration() = doCheck("DestructuringDeclaration.kt")

    fun testDefaultParameterValues() = doCheck("DefaultParameterValues.kt")

    fun testParameterPropertyWithAnnotation() = doCheck("ParameterPropertyWithAnnotation.kt")

    fun testParametersWithDefaultValues() = doCheck("ParametersWithDefaultValues.kt")

    fun testUnexpectedContainerException() = doCheck("UnexpectedContainerException.kt")

    fun testWhenStringLiteral() = doCheck("WhenStringLiteral.kt")

    // KTIJ-38566
    fun _testWhenAndDestructing() = doCheck("WhenAndDestructing.kt") { testName, file -> check(testName, file, false) }

    fun testSuperCalls() = doCheck("SuperCalls.kt")

    fun testConstructors() = doCheck("Constructors.kt")

    fun testReceiverFun() = doCheck("ReceiverFun.kt")

    fun testAnonymous() = doCheck("Anonymous.kt")

    fun testAnnotationComplex() = doCheck("AnnotationComplex.kt")

    // KTIJ-38566
    fun _testParametersDisorder() = doCheck("ParametersDisorder.kt") { testName, file ->
        // disabled due to inconsistent parents for 2-receivers call (KT-22344)
        check(testName, file, false)
    }

    fun testLambdas() = doCheck("Lambdas.kt")

    fun testTypeReferences() = doCheck("TypeReferences.kt")

    fun testDelegate() = doCheck("Delegate.kt")

    fun testConstructorDelegate() = doCheck("ConstructorDelegate.kt")

    fun testLambdaReturn() = doCheck("LambdaReturn.kt")

    fun testReified() = doCheck("Reified.kt")

    fun testReifiedReturnType() = doCheck("ReifiedReturnType.kt")

    // KTIJ-38566
    fun _testReifiedParameters() = doCheck("ReifiedParameters.kt")

    fun testSuspend() = doCheck("Suspend.kt")

    fun testDeprecatedHidden() = doCheck("DeprecatedHidden.kt")

    fun testTryCatch() = doCheck("TryCatch.kt")

    fun testAnnotatedExpressions() = doCheck("AnnotatedExpressions.kt")

    fun testNonTrivialIdentifiers() = doCheck("NonTrivialIdentifiers.kt")

    fun testTypeAliasExpansionWithOtherAliasInArgument() = doCheck("TypeAliasExpansionWithOtherAliasInArgument.kt")

    fun testComments() = doCheck("Comments.kt")

    fun testBrokenDataClass() = doCheck("BrokenDataClass.kt")
}