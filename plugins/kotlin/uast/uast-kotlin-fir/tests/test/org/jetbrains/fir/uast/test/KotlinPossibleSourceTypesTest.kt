// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.fir.uast.test

import com.intellij.platform.uast.testFramework.common.PossibleSourceTypesTestBase
import com.intellij.platform.uast.testFramework.common.allUElementSubtypes
import org.jetbrains.fir.uast.test.env.kotlin.AbstractFirUastTest
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.uast.UCallableReferenceExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UField
import org.jetbrains.uast.UFile
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.test.kotlin.TEST_KOTLIN_MODEL_PATH
import java.nio.file.Path

class KotlinPossibleSourceTypesTest : AbstractFirUastTest(), PossibleSourceTypesTestBase {

    override val pluginMode: KotlinPluginMode
        get() = KotlinPluginMode.K2

    override val testBasePath: Path = TEST_KOTLIN_MODEL_PATH

    override fun check(filePath: String, file: UFile) {
        val psiFile = file.sourcePsi
        for (uastType in allUElementSubtypes) {
            checkConsistencyWithRequiredTypes(psiFile, uastType)
        }
        checkConsistencyWithRequiredTypes(psiFile, UClass::class.java, UMethod::class.java, UField::class.java)
        checkConsistencyWithRequiredTypes(
            psiFile,
            USimpleNameReferenceExpression::class.java,
            UQualifiedReferenceExpression::class.java,
            UCallableReferenceExpression::class.java
        )
    }

    fun testAnnotationComplex() = doCheck("AnnotationComplex.kt")

    fun testAnnotationParameters() = doCheck("AnnotationParameters.kt")

    // KTIJ-38551
    fun _testAnonymous() = doCheck("Anonymous.kt")

    // KTIJ-38551
    fun _testBitwise() = doCheck("Bitwise.kt")

    fun testClassAnnotation() = doCheck("ClassAnnotation.kt")

    // KTIJ-38551
    fun _testConstructors() = doCheck("Constructors.kt")

    fun testConstructorDelegate() = doCheck("ConstructorDelegate.kt")

    fun testDefaultImpls() = doCheck("DefaultImpls.kt")

    fun testDefaultParameterValues() = doCheck("DefaultParameterValues.kt")

    fun testDelegate() = doCheck("Delegate.kt")

    fun testDeprecatedHidden() = doCheck("DeprecatedHidden.kt")

    // KTIJ-38551
    fun _testDestructuringDeclaration() = doCheck("DestructuringDeclaration.kt")

    fun testElvis() = doCheck("Elvis.kt")

    fun testEnumValueMembers() = doCheck("EnumValueMembers.kt")

    fun testEnumValuesConstructors() = doCheck("EnumValuesConstructors.kt")

    // KTIJ-38551
    fun _testIfStatement() = doCheck("IfStatement.kt")

    fun testInnerClasses() = doCheck("InnerClasses.kt")

    // KTIJ-38551
    fun _testLambdaReturn() = doCheck("LambdaReturn.kt")

    // KTIJ-38551
    fun _testLambdas() = doCheck("Lambdas.kt")

    // KTIJ-38551
    fun _testLocalDeclarations() = doCheck("LocalDeclarations.kt")

    // KTIJ-38551
    fun _testLocalVariableWithAnnotation() = doCheck("LocalVariableWithAnnotation.kt")

    fun testParameterPropertyWithAnnotation() = doCheck("ParameterPropertyWithAnnotation.kt")

    fun testParametersWithDefaultValues() = doCheck("ParametersWithDefaultValues.kt")

    fun testParametersDisorder() = doCheck("ParametersDisorder.kt")

    fun testPropertyAccessors() = doCheck("PropertyAccessors.kt")

    fun testPropertyInitializer() = doCheck("PropertyInitializer.kt")

    fun testPropertyInitializerWithoutSetter() = doCheck("PropertyInitializerWithoutSetter.kt")

    fun testPropertyWithAnnotation() = doCheck("PropertyWithAnnotation.kt")

    fun testReceiverFun() = doCheck("ReceiverFun.kt")

    fun testReified() = doCheck("Reified.kt")

    fun testReifiedParameters() = doCheck("ReifiedParameters.kt")

    fun testReifiedReturnType() = doCheck("ReifiedReturnType.kt")

    fun testQualifiedConstructorCall() = doCheck("QualifiedConstructorCall.kt")

    fun testSimple() = doCheck("Simple.kt")

    fun testStringTemplate() = doCheck("StringTemplate.kt")

    // KTIJ-38551
    fun _testStringTemplateComplex() = doCheck("StringTemplateComplex.kt")

    // KTIJ-38551
    fun _testStringTemplateComplexForUInjectionHost() = doCheck("StringTemplateComplexForUInjectionHost.kt")

    fun testSuperCalls() = doCheck("SuperCalls.kt")

    fun testSuspend() = doCheck("Suspend.kt")

    fun testTryCatch() = doCheck("TryCatch.kt")

    // KTIJ-38551
    fun _testTypeReferences() = doCheck("TypeReferences.kt")

    fun testUnexpectedContainer() = doCheck("UnexpectedContainerException.kt")

    // KTIJ-38551
    fun _testWhenAndDestructing() = doCheck("WhenAndDestructing.kt")

    // KTIJ-38551
    fun _testWhenIs() = doCheck("WhenIs.kt")

    fun testWhenStringLiteral() = doCheck("WhenStringLiteral.kt")
}