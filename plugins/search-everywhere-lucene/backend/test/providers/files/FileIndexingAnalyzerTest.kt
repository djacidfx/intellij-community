package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.searchEverywhereLucene.backend.AnalyzersTestBase
import com.intellij.searchEverywhereLucene.backend.providers.files.FileIndex.Companion.getIndexingAnalyzer
import org.junit.jupiter.api.Test

/**
 * Tests for the indexing analyzers (FileNameAnalyzer, FilePathAnalyzer, FileTypeAnalyzer)
 * used for document indexing.
 */
class FileIndexingAnalyzerTest : AnalyzersTestBase() {

  val indexingAnalyzer = getIndexingAnalyzer()

  // TODO implement this processing:
  // create mock VFS file -> use FileIndex.getDocument(file) to obtain the Doc -> then obtain a specific Term from the doc to tokenize and allow using the tooling from AnalyzersTestBase


  @Test
  fun `test FileNameAnalyzer`() {

    tokenizing(indexingAnalyzer, "SearchEveryWhereUI.java")
      .print()
      .noDuplicateTokens()

    // TODO: Add tests for FileNameAnalyzer
    // This analyzer handles the file name tokenization during indexing
  }


  @Test
  fun `test FilePathAnalyzer`() {
    // TODO: Add tests for FilePathAnalyzer
    // This analyzer handles path hierarchy tokenization during indexing
  }

  @Test
  fun `test FileTypeAnalyzer`() {
    // TODO: Add tests for FileTypeAnalyzer
    // This analyzer handles file type tokenization during indexing
  }
}
