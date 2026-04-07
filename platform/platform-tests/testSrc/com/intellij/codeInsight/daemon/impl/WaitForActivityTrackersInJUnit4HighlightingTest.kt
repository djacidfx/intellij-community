// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.daemon.impl

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.observation.ActivityKey
import com.intellij.platform.backend.observation.ActivityTracker
import com.intellij.platform.backend.observation.trackActivity
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.annotations.Nls
import org.junit.rules.DisableOnDebug
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies that [TestDaemonCodeAnalyzerImpl.waitForAllThingsBeforeDaemonStart]
 * waits for activity trackers to complete before starting highlighting passes.
 */
class WaitForActivityTrackersInJUnit4HighlightingTest : BasePlatformTestCase() {

  init {
    asOuterRule(DisableOnDebug(Timeout(30, TimeUnit.SECONDS)))
  }

  private object TestActivityKey : ActivityKey {
    override val presentableName: @Nls String get() = "Test background activity"
  }

  fun testDoHighlightingWaitsForActivityKeys() {
    myFixture.configureByText("test.txt", "hello")

    val activityStartedLatch = CountDownLatch(1)
    val activityCompleted = AtomicBoolean(false)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    try {
      // Start a tracked activity that takes some time
      scope.launch {
        project.trackActivity(TestActivityKey) {
          activityStartedLatch.countDown()
          delay(5.seconds)
          activityCompleted.set(true)
        }
      }

      // Wait until the tracked activity has started
      activityStartedLatch.await()

      // doHighlighting() should wait for the activity to complete before running passes
      myFixture.doHighlighting()

      assertTrue("Activity tracker should have completed before highlighting", activityCompleted.get())
    }
    finally {
      scope.cancel()
    }
  }

  fun testDoHighlightingWaitsForActivityTrackers() {
    myFixture.configureByText("test.txt", "hello")

    val completion = CompletableDeferred<Unit>()

    val tracker = object : ActivityTracker {
      override val presentableName: String = "Test EP tracker"
      override suspend fun isInProgress(project: Project): Boolean = !completion.isCompleted
      override suspend fun awaitConfiguration(project: Project) = completion.await()
    }

    ExtensionTestUtil.maskExtensions(
      ExtensionPointName.create<ActivityTracker>("com.intellij.activityTracker"),
      listOf(tracker),
      testRootDisposable
    )

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      scope.launch {
        delay(5.seconds)
        completion.complete(Unit)
      }

      myFixture.doHighlighting()

      assertTrue("ActivityTracker EP should have completed before highlighting", completion.isCompleted)
    }
    finally {
      scope.cancel()
    }
  }

  fun testDoHighlightingSkipsActivityKeyWhenMustWaitForSmartModeIsFalse() {
    myFixture.configureByText("test.txt", "hello")

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    try {
      // Start a tracked activity that never completes on its own
      scope.launch {
        project.trackActivity(TestActivityKey) {
          awaitCancellation()
        }
      }

      // Disable waiting for smart mode (simulates dumb-mode test variant)
      CodeInsightTestFixtureImpl.mustWaitForSmartMode(false, testRootDisposable)

      // doHighlighting() should NOT wait for the activity — would hang if it did
      myFixture.doHighlighting()
    }
    finally {
      scope.cancel()
    }
  }

  fun testDoHighlightingSkipsActivityTrackerEPWhenMustWaitForSmartModeIsFalse() {
    myFixture.configureByText("test.txt", "hello")

    val tracker = object : ActivityTracker {
      override val presentableName: String = "Test EP tracker (never completes)"
      override suspend fun isInProgress(project: Project): Boolean = true
      override suspend fun awaitConfiguration(project: Project) = awaitCancellation()
    }

    ExtensionTestUtil.maskExtensions(
      ExtensionPointName.create<ActivityTracker>("com.intellij.activityTracker"),
      listOf(tracker),
      testRootDisposable
    )

    // Disable waiting for smart mode (simulates dumb-mode test variant)
    CodeInsightTestFixtureImpl.mustWaitForSmartMode(false, testRootDisposable)

    // doHighlighting() should NOT wait for the activity tracker EP — would hang if it did
    myFixture.doHighlighting()
  }
}
