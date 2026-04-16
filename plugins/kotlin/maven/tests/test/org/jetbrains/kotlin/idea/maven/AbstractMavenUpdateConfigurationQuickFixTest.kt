// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.maven

import com.intellij.maven.testFramework.assertWithinTimeout
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.core.FileComparisonFailedError
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.util.ThrowableRunnable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.kotlin.idea.base.test.KotlinRoot
import org.jetbrains.kotlin.idea.test.runAll
import org.junit.Assert
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KMutableProperty0

abstract class AbstractMavenUpdateConfigurationQuickFixTest : KotlinMavenImportingTestCase() {
    private lateinit var _codeInsightTestFixture: CodeInsightTestFixtureImpl
    protected val codeInsightTestFixture: CodeInsightTestFixtureImpl
        get() = _codeInsightTestFixture

    private val artifactDownloadingScheduled = AtomicInteger()
    private val artifactDownloadingFinished = AtomicInteger()

    override fun setUp() {
        super.setUp()
        project.messageBus.connect(testRootDisposable)
            .subscribe(MavenImportListener.TOPIC, object : MavenImportListener {
                override fun artifactDownloadingScheduled() {
                    artifactDownloadingScheduled.incrementAndGet()
                }

                override fun artifactDownloadingFinished() {
                    artifactDownloadingFinished.incrementAndGet()
                }
            })
    }

    override fun tearDown() = runBlocking {
        try {
            waitForScheduledArtifactDownloads()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private suspend fun waitForScheduledArtifactDownloads() {
        assertWithinTimeout {
            val scheduled = artifactDownloadingScheduled.get()
            val finished = artifactDownloadingFinished.get()
            Assert.assertEquals("Expected $scheduled artifact downloads, but finished $finished", scheduled, finished)
        }
    }

    protected abstract val testRoot: String

    protected fun getTestDataPath(): String =
        KotlinRoot.DIR.resolve(testRoot).resolve(getTestName(true)).path.substringBefore('_')

    override fun setUpFixtures() {
        testFixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(name).fixture
        val fixture = IdeaTestFixtureFactory.getFixtureFactory().createCodeInsightFixture(testFixture) as CodeInsightTestFixtureImpl
        fixture.setUp()
        _codeInsightTestFixture = fixture
    }

    override fun tearDownFixtures() {
        runAll(
            ThrowableRunnable { _codeInsightTestFixture.tearDown() },
            ThrowableRunnable {
                @Suppress("UNCHECKED_CAST")
                (this::_codeInsightTestFixture as KMutableProperty0<CodeInsightTestFixture?>).set(null)
            },
            ThrowableRunnable { setTestFixtureNull() }
        )
    }


    protected suspend fun doTest(intentionName: String) {
        val pomVFile = createProjectSubFile("pom.xml", File(getTestDataPath(), "pom.xml").readText())
        val sourceVFile = createProjectSubFile("src/main/kotlin/src.kt", File(getTestDataPath(), "src.kt").readText())
        LocalFileSystem.getInstance().refreshFiles(listOf(pomVFile, sourceVFile))
        projectPom = pomVFile
        addPom(projectPom)
        importProjectAsync()
        withContext(Dispatchers.EDT) {
            writeIntentReadAction {
                assertTrue(ModuleRootManager.getInstance(testFixture.module).fileIndex.isInSourceContent(sourceVFile))
                with(codeInsightTestFixture) {
                    configureFromExistingVirtualFile(sourceVFile)
                    canChangeDocumentDuringHighlighting(true)
                    launchAction(codeInsightTestFixture.findSingleIntention(intentionName))
                }
                FileDocumentManager.getInstance().saveAllDocuments()
                checkResult(pomVFile)
            }
        }
    }

    protected fun checkResult(file: VirtualFile) {
        val expectedPath = File(getTestDataPath(), "pom.xml.after")
        val expectedContent = FileUtil.loadFile(expectedPath, true)
        val actualContent = LoadTextUtil.loadText(file).toString()
        if (actualContent != expectedContent) {
            throw FileComparisonFailedError("pom.xml doesn't match", expectedContent, actualContent, expectedPath.path)
        }
    }
}
