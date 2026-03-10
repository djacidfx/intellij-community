package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.ide.actions.searcheverywhere.FileSearchEverywhereContributor
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.platform.searchEverywhere.SeItemsProvider
import com.intellij.platform.searchEverywhere.backend.providers.files.SeFilesProviderFactory
import com.intellij.searchEverywhereLucene.backend.SeItemsProviderPerformanceTestBase
import com.intellij.testFramework.junit5.TestApplication
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
@TestApplication
class FileSearchPerformanceTest : SeItemsProviderPerformanceTestBase() {

  private val workspaceFileIndex get() = WorkspaceFileIndex.getInstance(project)

  companion object {
    private val communityPath = Path(PathManager.getCommunityHomePath())
    private val communityVirtualFile = VfsUtil.findFile(communityPath, true)!!
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

    // Initialize and wait for the index to be built
    val luceneIndex = FileIndex.getInstance(project)
    luceneIndex.awaitIndexCreation()

    DumbService.getInstance(project).waitForSmartMode()
  }

  @Test
  fun `benchmark Lucene vs Standard search`() {
    val searchPattern = "a"
    val luceneProvider = createProvider()
    val standardProvider = createStandardProvider()
    newBenchmarkForProviders(luceneProvider, standardProvider)
      .warmupIterations(1)
      .runs(2)
      .resultLimit(100)
      .inputQuery(searchPattern)
      .run()
      .printResults()
      .plot()
  }

  private fun createProvider(): SearchEverywhereLuceneFilesProvider {
    return SearchEverywhereLuceneFilesProvider(project).also { provider ->
      Disposer.register(projectModel.disposableRule.disposable, provider)
    }
  }

  private fun createStandardProvider(): SeItemsProvider = runBlocking {
    val dataContext = SimpleDataContext.getProjectContext(project)
    val event = AnActionEvent.createEvent(dataContext, null, "", ActionUiKind.NONE, null)
    val legacyContributor = FileSearchEverywhereContributor(event)

    SeFilesProviderFactory().getItemsProvider(project, legacyContributor = legacyContributor)
    ?: error("File Provider could not be created")
  }


}
