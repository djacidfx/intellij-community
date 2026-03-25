// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.gradle.scripting.k2.inspections

import org.jetbrains.plugins.gradle.frameworkSupport.GradleDsl
import org.jetbrains.plugins.gradle.testFramework.GradleTestFixtureBuilder
import org.jetbrains.plugins.gradle.testFramework.util.withBuildFile
import org.jetbrains.plugins.gradle.testFramework.util.withSettingsFile

internal val EMPTY_BUILD_SCRIPT_FIXTURE =
    GradleTestFixtureBuilder.create("empty-build-script") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("empty-build-script")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
        }
    }

internal val WITH_KOTLIN_OPTIONS_IN_ALL_PROJECTS_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-in-all-propjects") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-in-all-propjects")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
allprojects {
    tasks.withType<KotlinCompile>().configureEach {
        kotlinOptions.jvmTarget = "1.8"
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_AND_ASSIGNMENT_OPERATION_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-and-assignment-operation") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-and-assignment-operation")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
    kotlinOptions.freeCompilerArgs += "-Xexport-kdoc"
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_AND_ASSIGNMENT_OPERATION_TWO_PARAMS_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-and-assignment-operation-two-params") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-and-assignment-operation-two-params")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
    kotlinOptions.freeCompilerArgs += "-Xexport-kdoc" + "-Xopt-in=kotlin.RequiresOptIn"
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_AND_COMPILER_OPTIONS_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-and-compiler-options") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-and-compiler-options")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.dsl.JvmTarget")
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }

    val overriddenLanguageVersion = project.properties["kotlin.language.version"]?.toString()?.takeIf { it.isNotEmpty() }
    kotlinOptions {
        if (overriddenLanguageVersion != null) {
            languageVersion = overriddenLanguageVersion
            // We replace statements even in children
            freeCompilerArgs += "-Xsuppress-version-warnings"
        }
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_AND_FORBIDDEN_OPERATION_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-and-forbidden-operation") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-and-forbidden-operation")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.named("compileKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
    kotlinOptions.freeCompilerArgs -= "-Xexport-kdoc"
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_AND_FORBIDDEN_OPERATION_2_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-and-forbidden-operation-2") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-and-forbidden-operation-2")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs -= "-Xexport-kdoc"
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_AND_MINUS_OPERATOR_1_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-and-minus-operator-1") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-and-minus-operator-1")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        kotlinOptions {
            freeCompilerArgs = freeCompilerArgs - "-Xopt-in=kotlin.RequiresOptIn"
        }
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_AND_MINUS_OPERATOR_2_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-and-minus-operator-2") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-and-minus-operator-2")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        kotlinOptions.freeCompilerArgs = kotlinOptions.freeCompilerArgs - "-Xopt-in=kotlin.RequiresOptIn"
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_AND_MINUS_OPERATOR_3_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-and-minus-operator-3") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-and-minus-operator-3")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        kotlinOptions.freeCompilerArgs =
            kotlinOptions.freeCompilerArgs - "-Xopt-in=kotlin.RequiresOptIn" + "-opt-in=androidx.compose.animation.ExperimentalAnimationApi"
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_ADD_ALL_FROM_LIST_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-and-add-all-from-list") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-and-add-all-from-list")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += listOf("-module-name", "TheName")
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_MULTIPLE_ADDITION_1_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-multiple-addition-1") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-multiple-addition-1")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        kotlinOptions {
            freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn" + "-Xjvm-default=all"
        }
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_MULTIPLE_ADDITION_2_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-multiple-addition-2") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-multiple-addition-2")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        kotlinOptions {
            freeCompilerArgs = freeCompilerArgs + project.properties.get("A").toString() + project.properties.get("B").toString()
        }
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_MULTIPLE_ADDITION_3_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-multiple-addition-3") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-multiple-addition-3")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        kotlinOptions {
            freeCompilerArgs = freeCompilerArgs +
                    "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi" +
                    "-opt-in=androidx.compose.animation.ExperimentalAnimationApi" +
                    "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api" +
                    "-opt-in=com.google.accompanist.permissions.ExperimentalPermissionsApi"
        }
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_MULTIPLE_ADDITION_4_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-multiple-addition-4") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-multiple-addition-4")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        kotlinOptions {
            freeCompilerArgs += libraries.flatMap { listOf("-include-binary", it.path) } + "-include-binary, junit"
        }
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_MULTIPLE_ADDITION_5_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-multiple-addition-5") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-multiple-addition-5")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    dependencies {
        implementation(kotlin("stdlib-jdk8"))
    }

    tasks.withType<KotlinCompile>().all {
        kotlinOptions {
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
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_FREE_COMPILER_ARGS_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-free-compiler-args") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-free-compiler-args")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-Xopt-in=kotlin.RequiresOptIn"
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_FREE_COMPILER_ARGS_SET_LIST_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-free-compiler-args-set-list") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-free-compiler-args-set-list")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xjsr305=strict")
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_FREE_COMPILER_ARGS_WITH_SUPPRESS_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-free-compiler-args-with-suppress") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-free-compiler-args-set-list-with-suppress")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.withType<KotlinCompile> {
    kotlinOptions {
        @Suppress("SuspiciousCollectionReassignment")
        freeCompilerArgs += listOf("-Xopt-in=kotlin.RequiresOptIn")
        // KtExpressionImpl performs replaceExpression() and there calls KtPsiUtil.areParenthesesNecessary(). Inside, innerPriority is calculated
        // for DOT_QUALIFIED_EXPRESSION and it's 14, parentPriority is calculated for ANNOTATED_EXPRESSION is 15, and that's why () are added
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_WITH_JAVA_11_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-with-java-11") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-with-java-11")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = JavaVersion.VERSION_11.toString()
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_WITH_JAVA_VERSION_DEFINED_SEPARATELY_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-with-java-version-defined-separately") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-with-java-version-defined-separately")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
val javaVersion = JavaVersion.VERSION_1_8
tasks.withType(KotlinJvmCompile::class.java).configureEach {
    kotlinOptions {
        jvmTarget = javaVersion.toString()
        freeCompilerArgs += setOf(
            "-Xjvm-default=all",
        )
    }
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_WITH_JS_SOURCE_MAP_EMBED_SOURCES_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-with-js-source-map-embed-sources") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-with-js-source-map-embed-sources")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.withType<Kotlin2JsCompile>().configureEach {
    kotlinOptions.sourceMapEmbedSources = "inlining"
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_WITH_JS_SOURCE_MAP_NAMES_POLICY_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-with-js-source-map-names-policy") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-with-js-source-map-names-policy")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.withType<Kotlin2JsCompile>().configureEach {
    kotlinOptions.sourceMapNamesPolicy = "fully-qualified-names"
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_WITH_TYPE_OF_FQN_AND_CONFIGURE_EACH_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-with-type-of-fqn-and-configure-each") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-with-type-of-fqn-and-configure-each")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.withType<org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile>().configureEach {
    kotlinOptions.main = "noCall"
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_WITH_JS_TEST_ORDINARY_STRING_OPTION_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-with-js-test-ordinary-string-option") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-with-js-test-ordinary-string-option")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
tasks.withType<Kotlin2JsCompile>().configureEach {
    kotlinOptions.sourceMapPrefix = "myPrefix"
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_WITH_JVM_TARGET_9_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-with-jvm-target-9") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-with-jvm-target-9")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "9"
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_WITH_JVM_TARGET_DEFINED_WITH_ENUM_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-with-jvm-target-defined-with-enum") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-with-jvm-target-defined-with-enum")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            addImport("org.jetbrains.kotlin.config.ApiVersion")
            addImport("org.jetbrains.kotlin.config.LanguageVersion")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = JavaVersion.VERSION_1_8.toString()
    languageVersion = LanguageVersion.KOTLIN_2_1.toString()
    apiVersion = ApiVersion.KOTLIN_2_1.toString()
}
                    """.trimIndent()
                )
            }
        }
    }

internal val WITH_KOTLIN_OPTIONS_WITH_JVM_TARGET_SETTING_WITH_PROPERTIES_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-with-jvm-target-setting-with-properties") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-with-jvm-target-setting-with-properties")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
fun properties(key: String) = project.findProperty(key).toString()

val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = properties("javaVersion")
}
val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.kotlinOptions {
    jvmTarget = properties("javaVersion")
}
                    """.trimIndent()
                )
            }
        }
        withFile(
            "gradle.properties", /* language=TOML */ """
                    javaVersion=1.8
                    """.trimIndent()
        )
    }

internal val WITH_KOTLIN_OPTIONS_WITH_API_VERSION_AS_STRING_FIXTURE =
    GradleTestFixtureBuilder.create("with-kotlin-options-with-api-version-as-string") { gradleVersion ->
        withSettingsFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            setProjectName("with-kotlin-options-with-api-version-as-string")
        }
        withBuildFile(gradleVersion, gradleDsl = GradleDsl.KOTLIN) {
            addImport("org.jetbrains.kotlin.gradle.tasks.KotlinCompile")
            withKotlinJvmPlugin()
            withRepository { mavenCentral() }
            withPrefix {
                code(
                    """
val compileKotlin: KotlinCompile by tasks
compileKotlin.kotlinOptions {
    jvmTarget = "9"
    freeCompilerArgs += listOf("-module-name", "TheName")
    apiVersion = "1.9"
}
                    """.trimIndent()
                )
            }
        }
    }