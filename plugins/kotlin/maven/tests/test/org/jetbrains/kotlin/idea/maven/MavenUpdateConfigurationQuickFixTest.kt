// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.maven

import kotlinx.coroutines.runBlocking
import org.junit.Ignore
import org.junit.Test

class MavenUpdateConfigurationQuickFixTest12 : AbstractMavenUpdateConfigurationQuickFixTest() {

    override val testRoot: String
        get() = "maven/tests/testData/languageFeature"

    @Ignore("KTIJ-36424")
    @Test
    fun testUpdateLanguageVersion() = runBlocking {
        doTest("Set module language version to 1.1")
    }

    @Ignore("KTIJ-36424")
    @Test
    fun testUpdateLanguageVersionProperty() = runBlocking {
        doTest("Set module language version to 1.1")
    }

    @Ignore("KTIJ-36424")
    @Test
    fun testUpdateApiVersion() = runBlocking {
        doTest("Set module API version to 1.1")
    }

    @Ignore("KTIJ-36424")
    @Test
    fun testUpdateLanguageAndApiVersion() = runBlocking {
        doTest("Set module language version to 1.1")
    }

    @Test
    fun testAddKotlinReflect() = runBlocking {
        doTest("Add 'kotlin-reflect.jar' to the classpath")
    }
}