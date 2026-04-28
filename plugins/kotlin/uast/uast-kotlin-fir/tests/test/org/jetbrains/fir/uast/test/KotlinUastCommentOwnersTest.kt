// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.fir.uast.test

import org.jetbrains.uast.test.kotlin.TEST_KOTLIN_MODEL_PATH
import java.nio.file.Path

class KotlinUastCommentOwnersTest : AbstractFirUastCommentsTest() {

    override val testBasePath: Path = TEST_KOTLIN_MODEL_PATH

    fun testCommentOwners() = doCheck("CommentOwners.kt")

    fun testComments() = doCheck("Comments.kt")
}