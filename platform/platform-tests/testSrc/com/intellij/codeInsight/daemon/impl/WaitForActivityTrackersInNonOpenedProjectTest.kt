// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl

import com.intellij.codeInsight.daemon.impl.WaitForActivityTrackersInNonOpenedProjectTest.Companion.projectFixture
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.observation.ActivityKey
import com.intellij.platform.backend.observation.ActivityTracker
import com.intellij.platform.backend.observation.trackActivity
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.TestDisposable
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Nls
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that [com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl.instantiateAndRun] skips the activity tracker wait
 * when the project is not opened (lightweight test setup with [projectFixture] default `openAfterCreation = false`).
 *
 * This reproduces the scenario from IJPL-242138 where Python JUnit5 tests using a non-opened project
 * would hang because [com.intellij.ide.startup.StartupActivityTracker] reports `isInProgress = true`
 * from project creation, but startup activities are never triggered.
 */
@TestApplication
class WaitForActivityTrackersInNonOpenedProjectTest {
  companion object {
    private val tempDirFixture = tempPathFixture()
    private val projectFixture = projectFixture(tempDirFixture)

    @Suppress("unused")
    private val moduleFixture = projectFixture.moduleFixture(tempDirFixture, addPathToSourceRoot = true)
  }

  private val codeInsightFixture by codeInsightFixture(projectFixture, tempDirFixture)

  @Test
  fun `doHighlighting skips activity key wait when project is not opened`(@TestDisposable disposable: Disposable): Unit =
      timeoutRunBlocking(timeout = 30.seconds) {
          val project = projectFixture.get()
          codeInsightFixture.configureByText("test.txt", "hello")

          val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

          try {
              scope.launch {
                  project.trackActivity(TestActivityKey) {
                      awaitCancellation()
                  }
              }

              // doHighlighting() should NOT wait — project is not opened, so waitForConfiguration is skipped
              codeInsightFixture.doHighlighting()
          } finally {
              scope.cancel()
          }
      }

  @Test
  fun `doHighlighting skips activity tracker EP wait when project is not opened`(@TestDisposable disposable: Disposable): Unit =
      timeoutRunBlocking(timeout = 30.seconds) {
          codeInsightFixture.configureByText("test.txt", "hello")

          val tracker = object : ActivityTracker {
              override val presentableName: String = "Test EP tracker (never completes)"
              override suspend fun isInProgress(project: Project): Boolean = true
              override suspend fun awaitConfiguration(project: Project) = awaitCancellation()
          }

          ExtensionTestUtil.maskExtensions(
              ExtensionPointName.create<ActivityTracker>("com.intellij.activityTracker"),
              listOf(tracker),
              disposable,
          )

          // doHighlighting() should NOT wait — project is not opened, so waitForConfiguration is skipped
          codeInsightFixture.doHighlighting()
      }

  private object TestActivityKey : ActivityKey {
    override val presentableName: @Nls String get() = "Test background activity"
  }
}