// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.project

import com.intellij.configurationStore.SettingsSavingComponent
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.testFramework.ApplicationRule
import com.intellij.testFramework.TemporaryDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.ClassRule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

/**
 * Regression test for PY-89275: if a cancellation arrives at `templateAsync.await()` in
 * `ProjectManagerImpl.prepareNewProject` — i.e. after `ProjectImpl` is constructed and registered in
 * `ProjectIdsStorage`, but before `initProject` is entered — the partially-created project must still be disposed.
 * Without a caller-side try/catch in `prepareNewProject`, the `CancellationException` skips `initProject`'s
 * (pre-existing) internal catch and the project leaks through `ProjectIdsStorage.idsToProject` (unregistration
 * only happens in `ProjectImpl.dispose()`), which is what `_LastInSuiteTest.testProjectLeak` flags.
 *
 * To hit `templateAsync.await()` deterministically, the test installs a [SettingsSavingComponent] on the default
 * project whose `save()` suspends indefinitely via `awaitCancellation()`. This causes
 * `acquireTemplateProject → saveSettings → stateStore.save` to never complete, so the `async { acquireTemplateProject() }`
 * launched by `prepareNewProject` stays pending. Meanwhile, `prepareNewProject` still completes `instantiateProject`
 * and then suspends at `templateAsync.await()`. When the test cancels the outer scope, the cancellation is observed
 * precisely at that `await()` — outside `initProject`.
 *
 * Asserting [Project.isDisposed] is sufficient: if the project is disposed, `ProjectImpl.dispose()` has called
 * `unregisterProjectId`, removing it from `ProjectIdsStorage`.
 */
class ProjectCancellationLeakTest {
  companion object {
    @ClassRule
    @JvmField
    val appRule: ApplicationRule = ApplicationRule()
  }

  @Service(Service.Level.PROJECT)
  private class HangingSaver : SettingsSavingComponent {
    override suspend fun save() {
      saveEntered.countDown()
      awaitCancellation()
    }
  }

  @Test
  fun `project is disposed when open is cancelled at templateAsync await`() {
    val projectFile = TemporaryDirectory.generateTemporaryPath("template-hang-leak-test")
    projectFile.createDirectories()

    // Force-instantiate the SettingsSavingComponent on the default project. Going through `service()` calls
    // `ComponentStoreWithExtraComponents.initComponent`, which drops the cached list of saving components so
    // that the next `saveSettings` invocation picks it up.
    val defaultProject = ProjectManagerEx.getInstanceEx().defaultProject
    defaultProject.service<HangingSaver>()
    saveEntered = CountDownLatch(1)

    var captured: Project? = null
    val options = OpenProjectTask {
      forceOpenInNewFrame = true
      runConversionBeforeOpen = false
      runConfigurators = false
      showWelcomeScreen = false
      isNewProject = true
      useDefaultProjectAsTemplate = true  // forces templateAsync to be non-null
      projectName = "template-hang-leak-test"
      projectRootDir = projectFile
      beforeInit = { project -> captured = project }
    }

    @Suppress("RAW_SCOPE_CREATION")
    val openScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val openJob = openScope.async {
        ProjectManagerEx.getInstanceEx().openProjectAsync(projectFile, options)
      }

      // Wait until `HangingSaver.save()` is pending — instantiateProject will have already returned by then,
      // and prepareNewProject is (or is about to be) suspended at templateAsync.await().
      assertThat(saveEntered.await(30, TimeUnit.SECONDS))
        .describedAs("HangingSaver.save() must be invoked during template save")
        .isTrue
      // Give the coroutine a chance to reach templateAsync.await(). This is a small window but not a race:
      // even if the open job reaches initProject first, initProject will observe the same scope cancellation
      // and also route through the caller-side try/catch that this test is validating.
      Thread.sleep(50)
      openJob.cancel(CancellationException("test-induced cancellation"))
      runBlocking { runCatching { openJob.await() } }
    }
    finally {
      openScope.cancel()
    }

    val leaked = captured
    assertThat(leaked)
      .describedAs("beforeInit must have captured the ProjectImpl reference")
      .isNotNull
    assertThat(leaked!!.isDisposed)
      .describedAs(
        "Project must be disposed after cancelled open (otherwise it leaks through ProjectIdsStorage.idsToProject, " +
        "since unregisterProjectId is only called by ProjectImpl.dispose())"
      )
      .isTrue
  }
}

private lateinit var saveEntered: CountDownLatch
