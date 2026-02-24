// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.searchEverywhereLucene.backend

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.searchEverywhereLucene.backend.providers.files.FileIndex
import com.intellij.searchEverywhereLucene.backend.providers.files.LuceneFileIndexOperation
import com.intellij.searchEverywhereLucene.backend.providers.files.SearchEverywhereLuceneFilesProvider
import com.intellij.testFramework.PerformanceUnitTest
import com.intellij.testFramework.junit5.RegistryKey
import com.intellij.testFramework.junit5.StressTestApplication
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.workspaceModel.core.fileIndex.WorkspaceFileIndex
import com.intellij.workspaceModel.ide.registerProjectRootBlocking
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.Path

/**
 * Performance test for [SearchEverywhereLuceneFilesProvider].
 */
@StressTestApplication
@RegistryKey(key = "se.enable.non.indexable.files.contributor", value = "true")
@PerformanceUnitTest
class LuceneFileSearchPerformanceTest : SeItemsProviderPerformanceTestBase() {

  private val workspaceFileIndex get() = WorkspaceFileIndex.getInstance(project)

  companion object {
    private val communityPath = Path(PathManager.getCommunityHomePath())
    private val communityVirtualFile = VfsUtil.findFile(communityPath, true)!!

    private val nonIndexableFilesCount: Int = run {
      var nonIndexableFiles = 0
      VfsUtil.processFilesRecursively(NewVirtualFile.asCacheAvoiding(communityVirtualFile)) {
        nonIndexableFiles++
        true
      }
      nonIndexableFiles
    }
  }



  @BeforeEach
  fun createNonIndexableFileset(): Unit = runBlocking {
    Assumptions.assumeTrue(project.isOpen)

    runInEdtAndWait { registerProjectRootBlocking(project, communityPath) }

    Assumptions.assumeTrue(readAction { workspaceFileIndex.isInContent(communityVirtualFile) }) {
      "project root must be in content"
    }
    Assumptions.assumeFalse(readAction { workspaceFileIndex.isIndexable(communityVirtualFile) }) {
      "project root must be non-indexable"
    }

    // Initialize and wait for index to be built
    val luceneIndex = FileIndex.getInstance(project)
    luceneIndex.awaitIndexCreation()
  }

  @Test
  fun `benchmark a Lucene search`() {
    val searchPattern = "a"
    val provider = createProvider()
    benchmarkProvider(
      provider = provider,
      inputQuery = searchPattern,
      expectedMinResults = 1
    )
  }

  private fun createProvider(): SearchEverywhereLuceneFilesProvider {
    return SearchEverywhereLuceneFilesProvider(project).also { provider ->
      Disposer.register(projectModel.disposableRule.disposable, provider)
    }
  }
}
