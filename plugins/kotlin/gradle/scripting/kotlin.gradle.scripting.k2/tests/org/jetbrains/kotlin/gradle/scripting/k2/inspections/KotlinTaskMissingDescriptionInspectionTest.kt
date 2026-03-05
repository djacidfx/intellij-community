// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.gradle.scripting.k2.inspections

import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.K2GradleCodeInsightTestCase
import org.jetbrains.plugins.gradle.codeInspection.GradleTaskMissingDescriptionInspection
import org.jetbrains.plugins.gradle.testFramework.annotations.AllGradleVersionsSource
import org.jetbrains.plugins.gradle.testFramework.util.assertThatKotlinDslScriptsModelImportIsSupported
import org.jetbrains.plugins.gradle.tooling.annotation.TargetVersions
import org.junit.jupiter.params.ParameterizedTest

class KotlinTaskMissingDescriptionInspectionTest : K2GradleCodeInsightTestCase() {

    private fun runTest(
        gradleVersion: GradleVersion,
        test: () -> Unit
    ) {
        assertThatKotlinDslScriptsModelImportIsSupported(gradleVersion)
        testKotlinDslEmptyProject(gradleVersion) {
            codeInsightFixture.enableInspections(GradleTaskMissingDescriptionInspection::class.java)
            test()
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testMissing(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                "tasks.<weak_warning descr=\"Task is missing a description\">register</weak_warning>(\"someTask\") {}"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testMissingNoConfigBlock(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                "tasks.<weak_warning descr=\"Task is missing a description\">register</weak_warning>(\"someTask\")"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testMissingDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                "val task by tasks.<weak_warning descr=\"Task is missing a description\">registering</weak_warning> {}"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testMissingDelegationNoConfigBlock(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                "val task by tasks.<weak_warning descr=\"Task is missing a description\">registering</weak_warning>"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNotMissing(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks.register("someTask") {
                    description = "some description"
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNotMissingDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                val task by tasks.registering {
                    description = "some description"
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNotMissingSetter(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks.register("someTask") {
                    setDescription("some description")
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNotMissingSetterDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                val task by tasks.registering {
                    setDescription("some description")
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNestedDescriptionAssignment(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                var cond = true
                tasks.register("someTask") {
                    if (cond) {
                        description = "some description"
                    } else {
                        description = "some other description"
                    }
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNestedDescriptionAssignmentDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                var cond = true
                val task by tasks.registering {
                    if (cond) {
                        description = "some description"
                    } else {
                        description = "some other description"
                    }
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNestedDescriptionSetters(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                var cond = true
                tasks.register("someTask") {
                    if (cond) {
                        setDescription("some description")
                    } else {
                        setDescription("some other description")
                    }
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNestedDescriptionSettersDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                var cond = true
                val task by tasks.registering {
                    if (cond) {
                        setDescription("some description")
                    } else {
                        setDescription("some other description")
                    }
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testInsideTasksBlock(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks {
                    <weak_warning>register</weak_warning>("someTask")
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testInsideTasksBlockDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks {
                    val someTask by <weak_warning>registering</weak_warning> {}
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testInsideTasksBlockDelegationNoConfigBlock(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks {
                    val someTask by <weak_warning>registering</weak_warning>
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testInsideTasksBlockNotMissing(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks {
                    register("someTask") {
                        description = "some description"
                    }
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testInsideTasksBlockDelegationNotMissing(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks {
                    val someTask by registering {
                        description = "some description"
                    }
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNotMissingWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks.register<Copy>("someTask") {
                    description = "some description"
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNotMissingDelegationWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                val task by tasks.registering(Copy::class) {
                    description = "some description"
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNotMissingSetterWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks.register<Copy>("someTask") {
                    setDescription("some description")
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testNotMissingSetterDelegationWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                val task by tasks.registering(Copy::class) {
                    setDescription("some description")
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testInsideTasksBlockNotMissingWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks {
                    register<Copy>("someTask") {
                        description = "some description"
                    }
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testInsideTasksBlockDelegationNotMissingWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks {
                    val someTask by registering(Copy::class) {
                        description = "some description"
                    }
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testMissingWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                "tasks.<weak_warning>register</weak_warning><Copy>(\"someTask\") {}"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testMissingNoConfigBlockWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                "tasks.<weak_warning>register</weak_warning><Copy>(\"someTask\")"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testMissingDelegationWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                "val task by tasks.<weak_warning>registering</weak_warning>(Copy::class) {}"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testMissingDelegationNoConfigBlockWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                "val task by tasks.<weak_warning>registering</weak_warning>(Copy::class)"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testInsideTasksBlockWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks {
                    <weak_warning>register</weak_warning><Copy>("someTask")
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testInsideTasksBlockDelegationWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks {
                    val someTask by <weak_warning>registering</weak_warning>(Copy::class) {}
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testInsideTasksBlockDelegationNoConfigBlockWithType(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testHighlighting(
                """
                tasks {
                    val someTask by <weak_warning>registering</weak_warning>(Copy::class)
                }
                """.trimIndent()
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testAdding(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testIntention(
                """
                tasks.register<caret>("someTask") {}
                """.trimIndent(),
                """
                tasks.register("someTask") {
                    description = "<caret>"
                }
                """.trimIndent(),
                "Add a description"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testAddingDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testIntention(
                """
                val task by tasks.registering<caret> {}
                """.trimIndent(),
                """
                val task by tasks.registering {
                    description = "<caret>"
                }
                """.trimIndent(),
                "Add a description"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testAddingBeforeAnyElement(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testIntention(
                """
                tasks.register<caret>("someTask") {
                    group = "existing group"
                }
                """.trimIndent(),
                """
                tasks.register("someTask") {
                    description = "<caret>"
                    group = "existing group"
                }
                """.trimIndent(),
                "Add a description"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testAddingBeforeAnyElementOnlyDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testIntention(
                """
                val task by tasks.registering<caret> {
                    group = "existing group"
                }
                """.trimIndent(),
                """
                val task by tasks.registering {
                    description = "<caret>"
                    group = "existing group"
                }
                """.trimIndent(),
                "Add a description"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testAddingConfigBlock(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testIntention(
                """
                tasks.register<caret>("someTask")
                """.trimIndent(),
                """
                tasks.register("someTask") {
                    description = "<caret>"
                }
                """.trimIndent(),
                "Add a description"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testAddingConfigBlockDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testIntention(
                """
                val task by tasks.registering<caret>
                """.trimIndent(),
                """
                val task by tasks.registering {
                    description = "<caret>"
                }
                """.trimIndent(),
                "Add a description"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testAddingInsideTasksBlock(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testIntention(
                """
                tasks {
                    register<caret>("someTask")
                }
                """.trimIndent(),
                """
                tasks {
                    register("someTask") {
                        description = "<caret>"
                    }
                }
                """.trimIndent(),
                "Add a description"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testAddingInsideTasksBlockDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testIntention(
                """
                tasks {
                    val someTask by registering<caret> {}
                }
                """.trimIndent(),
                """
                tasks {
                    val someTask by registering {
                        description = "<caret>"
                    }
                }
                """.trimIndent(),
                "Add a description"
            )
        }
    }

    @ParameterizedTest
    @AllGradleVersionsSource
    @TargetVersions("6.0+")
    fun testAddingConfigBlockInsideTasksBlockDelegation(gradleVersion: GradleVersion) {
        runTest(gradleVersion) {
            testIntention(
                """
                tasks {
                    val someTask by registering<caret>
                }
                """.trimIndent(),
                """
                tasks {
                    val someTask by registering {
                        description = "<caret>"
                    }
                }
                """.trimIndent(),
                "Add a description"
            )
        }
    }
}