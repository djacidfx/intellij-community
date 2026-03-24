// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.gradle.scripting.k2.inspections

import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.K2GradleCodeInsightTestCase
import org.jetbrains.plugins.gradle.testFramework.GradleTestFixtureBuilder
import org.jetbrains.plugins.gradle.testFramework.annotations.AllGradleVersionsSource
import org.jetbrains.plugins.gradle.testFramework.util.assumeThatGradleIsAtLeast
import org.jetbrains.plugins.gradle.testFramework.util.assumeThatGradleIsOlderThan
import org.junit.Ignore
import org.junit.jupiter.params.ParameterizedTest

internal class KotlinOptionsToCompilerOptionsInspectionTest : K2GradleCodeInsightTestCase() {

    private fun runTest(
        gradleVersion: GradleVersion,
        projectFixture: GradleTestFixtureBuilder,
        test: () -> Unit
    ) {
        assumeThatGradleIsAtLeast(gradleVersion, "8.11")
        assumeThatGradleIsOlderThan(gradleVersion, "9.0.0")
        test(gradleVersion, projectFixture) {
            codeInsightFixture.enableInspections(KotlinOptionsToCompilerOptionsInspection::class.java)
            test()
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testAllProjects(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_IN_ALL_PROJECTS_FIXTURE) {
            testIntention(
                """
allprojects {
    tasks.withType<KotlinCompile>().configureEach {
        <caret>kotlinOptions.jvmTarget = "1.8"
    }
}
                """.trimIndent(),
                """
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

allprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testAssignmentOperation(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_AND_ASSIGNMENT_OPERATION_FIXTURE) {
            testIntention(
                """
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
    <caret>kotlinOptions.freeCompilerArgs += "-Xexport-kdoc"
}
                """.trimIndent(),
                """
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
    compilerOptions.freeCompilerArgs.add("-Xexport-kdoc")
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testAssignmentOperationTwoParams(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_AND_ASSIGNMENT_OPERATION_TWO_PARAMS_FIXTURE) {
            testIntention(
                """
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
    <caret>kotlinOptions.freeCompilerArgs += "-Xexport-kdoc" + "-Xopt-in=kotlin.RequiresOptIn"
}
                """.trimIndent(),
                """
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
    compilerOptions.freeCompilerArgs.addAll("-Xexport-kdoc", "-Xopt-in=kotlin.RequiresOptIn")
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testDontMergeConvertedOptionsToAnotherCompilerOptions(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_AND_COMPILER_OPTIONS_FIXTURE) {
            testIntention(
                """
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }

    val overriddenLanguageVersion = project.properties["kotlin.language.version"]?.toString()?.takeIf { it.isNotEmpty() }
    <caret>kotlinOptions {
        if (overriddenLanguageVersion != null) {
            languageVersion = overriddenLanguageVersion
            // We replace statements even in children
            freeCompilerArgs += "-Xsuppress-version-warnings"
        }
    }
}
                """.trimIndent(),
                """
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }

    val overriddenLanguageVersion = project.properties["kotlin.language.version"]?.toString()?.takeIf { it.isNotEmpty() }
    compilerOptions {
        if (overriddenLanguageVersion != null) {
            languageVersion.set(KotlinVersion.fromVersion(overriddenLanguageVersion))
            // We replace statements even in children
            freeCompilerArgs.add("-Xsuppress-version-warnings")
        }
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testDontReplaceIfForbiddenOperation(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_AND_FORBIDDEN_OPERATION_FIXTURE) {
            testNoIntentions(
                """
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
    <caret>kotlinOptions.freeCompilerArgs -= "-Xexport-kdoc"
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testDontReplaceIfForbiddenOperation2(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_AND_FORBIDDEN_OPERATION_2_FIXTURE) {
            testNoIntentions(
                """
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    <caret>kotlinOptions {
        freeCompilerArgs -= "-Xexport-kdoc"
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testDontReplaceInKotlinFile(gradleVersion: GradleVersion) {
        runTest(gradleVersion, EMPTY_BUILD_SCRIPT_FIXTURE) {
            testNoIntentions(
                "src/main/kotlin/Test.kt",
                """
class Test{
    var parameter = 0
}

fun main() {
    val kotlinOptions = Test()
    <caret>kotlinOptions.parameter = 1
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testDontReplaceWithMinusOperator1(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_AND_MINUS_OPERATOR_1_FIXTURE) {
            testNoIntentions(
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        <caret>kotlinOptions {
            freeCompilerArgs = freeCompilerArgs - "-Xopt-in=kotlin.RequiresOptIn"
        }
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testDontReplaceWithMinusOperator2(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_AND_MINUS_OPERATOR_2_FIXTURE) {
            testNoIntentions(
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        <caret>kotlinOptions.freeCompilerArgs = kotlinOptions.freeCompilerArgs - "-Xopt-in=kotlin.RequiresOptIn"
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @Ignore("KTIJ-38171")
    @ParameterizedTest
    @AllGradleVersionsSource
    fun testDontReplaceWithMinusOperatorAndExpressionOnTheRightSide1(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_AND_MINUS_OPERATOR_2_FIXTURE) {
            testNoIntentions(
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        kotlinOptions.freeCompilerArgs = <caret>kotlinOptions.freeCompilerArgs - "-Xopt-in=kotlin.RequiresOptIn"
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testDontReplaceWithMinusOperator3(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_AND_MINUS_OPERATOR_3_FIXTURE) {
            testNoIntentions(
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        <caret>kotlinOptions.freeCompilerArgs =
            kotlinOptions.freeCompilerArgs - "-Xopt-in=kotlin.RequiresOptIn" + "-opt-in=androidx.compose.animation.ExperimentalAnimationApi"
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @Ignore("KTIJ-38171")
    @ParameterizedTest
    @AllGradleVersionsSource
    fun testDontReplaceWithMinusOperatorAndExpressionOnTheRightSide2(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_AND_MINUS_OPERATOR_3_FIXTURE) {
            testNoIntentions(
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        kotlinOptions.freeCompilerArgs =
            <caret>kotlinOptions.freeCompilerArgs - "-Xopt-in=kotlin.RequiresOptIn" + "-opt-in=androidx.compose.animation.ExperimentalAnimationApi"
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testFreeCompilerArgsAddAllFromList(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_ADD_ALL_FROM_LIST_FIXTURE) {
            testIntention(
                """
tasks.withType<KotlinCompile> {
    <caret>kotlinOptions {
        freeCompilerArgs += listOf("-module-name", "TheName")
    }
}
                """.trimIndent(),
                """
tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll(listOf("-module-name", "TheName"))
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testFreeCompilerArgsMultipleAddition1(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_MULTIPLE_ADDITION_1_FIXTURE) {
            testIntention(
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        <caret>kotlinOptions {
            freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn" + "-Xjvm-default=all"
        }
    }
}
                """.trimIndent(),
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        compilerOptions {
            freeCompilerArgs.addAll("-Xopt-in=kotlin.RequiresOptIn", "-Xjvm-default=all")
        }
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testFreeCompilerArgsMultipleAddition2(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_MULTIPLE_ADDITION_2_FIXTURE) {
            testIntention(
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        <caret>kotlinOptions {
            freeCompilerArgs = freeCompilerArgs + project.properties.get("A").toString() + project.properties.get("B").toString()
        }
    }
}
                """.trimIndent(),
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        compilerOptions {
            freeCompilerArgs.addAll(project.properties.get("A").toString(), project.properties.get("B").toString())
        }
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testFreeCompilerArgsMultipleAddition3(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_MULTIPLE_ADDITION_3_FIXTURE) {
            testIntention(
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        <caret>kotlinOptions {
            freeCompilerArgs = freeCompilerArgs +
                    "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi" +
                    "-opt-in=androidx.compose.animation.ExperimentalAnimationApi" +
                    "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api" +
                    "-opt-in=com.google.accompanist.permissions.ExperimentalPermissionsApi"
        }
    }
}
                """.trimIndent(),
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        compilerOptions {
            freeCompilerArgs.addAll(
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=com.google.accompanist.permissions.ExperimentalPermissionsApi"
            )
        }
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @Ignore("KTIJ-38174")
    @ParameterizedTest
    @AllGradleVersionsSource
    fun testFreeCompilerArgsMultipleAddition4(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_MULTIPLE_ADDITION_4_FIXTURE) {
            testIntention(
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        <caret>kotlinOptions {
            freeCompilerArgs += libraries.flatMap { listOf("-include-binary", it.path) } + "-include-binary, junit"
        }
    }
}
                """.trimIndent(),
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        compilerOptions {
            freeCompilerArgs.addAll(libraries.flatMap { listOf("-include-binary", it.path) }, "-include-binary, junit")
        }
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testFreeCompilerArgsMultipleAddition5(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_MULTIPLE_ADDITION_5_FIXTURE) {
            testIntention(
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        <caret>kotlinOptions {
            freeCompilerArgs = freeCompilerArgs + listOf(
                "-Xopt-in=kotlin.RequiresOptIn",
                "-Xopt-in=kotlin.ExperimentalStdlibApi",
                "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-Xopt-in=kotlinx.coroutines.FlowPreview",
                "-Xopt-in=kotlin.time.ExperimentalTime",
                "-Xopt-in=kotlin.RequiresOptIn",
                "-Xjvm-default=all",
                "-XXLanguage:+DataObjects",
                "-Xcontext-receivers"
            )
        }
    }
}
                """.trimIndent(),
                """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        compilerOptions {
            freeCompilerArgs.addAll(
                listOf(
                    "-Xopt-in=kotlin.RequiresOptIn",
                    "-Xopt-in=kotlin.ExperimentalStdlibApi",
                    "-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                    "-Xopt-in=kotlinx.coroutines.FlowPreview",
                    "-Xopt-in=kotlin.time.ExperimentalTime",
                    "-Xopt-in=kotlin.RequiresOptIn",
                    "-Xjvm-default=all",
                    "-XXLanguage:+DataObjects",
                    "-Xcontext-receivers"
                )
            )
        }
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testFreeCompilerArgsPlusFreeCompilerArgs(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_FREE_COMPILER_ARGS_FIXTURE) {
            testIntention(
                """
tasks.withType<KotlinCompile> {
    <caret>kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn"
    }
}
                """.trimIndent(),
                """
tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xopt-in=kotlin.RequiresOptIn")
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testFreeCompilerArgsSetList(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_FREE_COMPILER_ARGS_SET_LIST_FIXTURE) {
            testIntention(
                """
tasks.withType<KotlinCompile> {
    <caret>kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}
                """.trimIndent(),
                """
tasks.withType<KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.set(listOf("-Xjsr305=strict"))
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testFreeCompilerArgsWithSuppress(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_FREE_COMPILER_ARGS_WITH_SUPPRESS_FIXTURE) {
            testIntention(
                """
tasks.withType<KotlinCompile> {
    <caret>kotlinOptions {
        @Suppress("SuspiciousCollectionReassignment")
        freeCompilerArgs += listOf("-Xopt-in=kotlin.RequiresOptIn")
        // KtExpressionImpl performs replaceExpression() and there calls KtPsiUtil.areParenthesesNecessary(). Inside, innerPriority is calculated
        // for DOT_QUALIFIED_EXPRESSION and it's 14, parentPriority is calculated for ANNOTATED_EXPRESSION is 15, and that's why () are added
    }
}
                """.trimIndent(),
                """
tasks.withType<KotlinCompile> {
    compilerOptions {
        @Suppress("SuspiciousCollectionReassignment")
        (freeCompilerArgs.addAll(listOf("-Xopt-in=kotlin.RequiresOptIn")))
        // KtExpressionImpl performs replaceExpression() and there calls KtPsiUtil.areParenthesesNecessary(). Inside, innerPriority is calculated
        // for DOT_QUALIFIED_EXPRESSION and it's 14, parentPriority is calculated for ANNOTATED_EXPRESSION is 15, and that's why () are added
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testJava11(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_WITH_JAVA_11_FIXTURE) {
            testIntention(
                """
val compileKotlin: KotlinCompile by tasks
compileKotlin.<caret>kotlinOptions {
    jvmTarget = JavaVersion.VERSION_11.toString()
}
                """.trimIndent(),
                """
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testJavaVersionDefinedSeparately(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_WITH_JAVA_VERSION_DEFINED_SEPARATELY_FIXTURE) {
            testIntention(
                """
val javaVersion = JavaVersion.VERSION_1_8
tasks.withType(KotlinJvmCompile::class.java).configureEach {
    <caret>kotlinOptions {
        jvmTarget = javaVersion.toString()
        freeCompilerArgs += setOf(
            "-Xjvm-default=all",
        )
    }
}
                """.trimIndent(),
                """
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val javaVersion = JavaVersion.VERSION_1_8
tasks.withType(KotlinJvmCompile::class.java).configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString()))
        freeCompilerArgs.addAll(
            setOf(
                "-Xjvm-default=all",
            )
        )
    }
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testJsSourceMapEmbedSources(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_WITH_JS_SOURCE_MAP_EMBED_SOURCES_FIXTURE) {
            testIntention(
                """
tasks.withType<Kotlin2JsCompile>().configureEach {
    <caret>kotlinOptions.sourceMapEmbedSources = "inlining"
}
                """.trimIndent(),
                """
import org.jetbrains.kotlin.gradle.dsl.JsSourceMapEmbedMode

tasks.withType<Kotlin2JsCompile>().configureEach {
    compilerOptions.sourceMapEmbedSources.set(JsSourceMapEmbedMode.SOURCE_MAP_SOURCE_CONTENT_INLINING)
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testJsSourceMapNamesPolicy(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_WITH_JS_SOURCE_MAP_NAMES_POLICY_FIXTURE) {
            testIntention(
                """
tasks.withType<Kotlin2JsCompile>().configureEach {
    <caret>kotlinOptions.sourceMapNamesPolicy = "fully-qualified-names"
}
                """.trimIndent(),
                """
import org.jetbrains.kotlin.gradle.dsl.JsSourceMapNamesPolicy

tasks.withType<Kotlin2JsCompile>().configureEach {
    compilerOptions.sourceMapNamesPolicy.set(JsSourceMapNamesPolicy.SOURCE_MAP_NAMES_POLICY_FQ_NAMES)
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testJsTasksWithTypeOfFQNandConfigureEach(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_WITH_TYPE_OF_FQN_AND_CONFIGURE_EACH_FIXTURE) {
            testIntention(
                """
tasks.withType<org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile>().configureEach {
    <caret>kotlinOptions.main = "noCall"
}
                """.trimIndent(),
                """
import org.jetbrains.kotlin.gradle.dsl.JsMainFunctionExecutionMode

tasks.withType<org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile>().configureEach {
    compilerOptions.main.set(JsMainFunctionExecutionMode.NO_CALL)
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testJsTestOrdinaryStringOption(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_WITH_JS_TEST_ORDINARY_STRING_OPTION_FIXTURE) {
            testIntention(
                """
tasks.withType<Kotlin2JsCompile>().configureEach {
    <caret>kotlinOptions.sourceMapPrefix = "myPrefix"
}
                """.trimIndent(),
                """
tasks.withType<Kotlin2JsCompile>().configureEach {
    compilerOptions.sourceMapPrefix.set("myPrefix")
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testJvmTarget9(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_WITH_JVM_TARGET_9_FIXTURE) {
            testIntention(
                """
val compileKotlin: KotlinCompile by tasks
compileKotlin.<caret>kotlinOptions {
    jvmTarget = "9"
}
                """.trimIndent(),
                """
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    jvmTarget.set(JvmTarget.JVM_9)
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testJvmTargetDefinedWithEnum(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_WITH_JVM_TARGET_DEFINED_WITH_ENUM_FIXTURE) {
            testIntention(
                """
val compileKotlin: KotlinCompile by tasks
compileKotlin.<caret>kotlinOptions {
    jvmTarget = JavaVersion.VERSION_1_8.toString()
    languageVersion = LanguageVersion.KOTLIN_2_1.toString()
    apiVersion = ApiVersion.KOTLIN_2_1.toString()
}
                """.trimIndent(),
                """
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    jvmTarget.set(JvmTarget.JVM_1_8)
    languageVersion.set(KotlinVersion.fromVersion(LanguageVersion.KOTLIN_2_1.toString()))
    apiVersion.set(KotlinVersion.fromVersion(ApiVersion.KOTLIN_2_1.toString()))
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    fun testJvmTargetSettingWithProperties(gradleVersion: GradleVersion) {
        runTest(gradleVersion, WITH_KOTLIN_OPTIONS_WITH_JVM_TARGET_SETTING_WITH_PROPERTIES_FIXTURE) {
            testIntention(
                """
fun properties(key: String) = project.findProperty(key).toString()

val compileKotlin: KotlinCompile by tasks
compileKotlin.<caret>kotlinOptions {
    jvmTarget = properties("javaVersion")
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = properties("javaVersion")
}
                """.trimIndent(),
                """
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

fun properties(key: String) = project.findProperty(key).toString()

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    jvmTarget.set(JvmTarget.fromTarget(properties("javaVersion")))
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = properties("javaVersion")
}
                """.trimIndent(),
                "Replace 'kotlinOptions' with 'compilerOptions'"
            )
        }
    }


}
