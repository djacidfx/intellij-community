package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.searchEverywhereLucene.backend.AnalyzersTestBase
import com.intellij.searchEverywhereLucene.backend.providers.files.FileIndex.Companion.FILE_RELATIVE_PATH
import com.intellij.searchEverywhereLucene.backend.providers.files.FileIndex.Companion.FILE_TYPE
import com.intellij.searchEverywhereLucene.backend.providers.files.FileIndex.Companion.getIndexingAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileNameAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FilePathAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileTypeAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME_ABBREVIATION
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME_PART
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILETYPE
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_PATH
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_PATH_SEGMENT
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

    // The file Name Analyzer should produce the same tokens given a filename than the FileSearchAnalyzer.
    tokenizing(indexingAnalyzer, "SearchEveryWhereUI.java")
      .print()
      .noDuplicateTokens()
      .producesSameTokensAs(FileNameAnalyzer())
      .producesToken("SearchEveryWhereUI.java", TOKEN_TYPE_PATH)
      .producesToken("java", TOKEN_TYPE_FILETYPE, 19, 23)
      .producesToken("searcheverywhereui", TOKEN_TYPE_FILENAME, 0, 18)
      .producesToken("search", TOKEN_TYPE_FILENAME_PART, 0, 6)
      .producesToken("every", TOKEN_TYPE_FILENAME_PART, 6, 11)
      .producesToken("where", TOKEN_TYPE_FILENAME_PART, 11, 16)
      .producesToken("sewui", TOKEN_TYPE_FILENAME_ABBREVIATION, 0, 18)
      .producesToken("ui", TOKEN_TYPE_FILENAME_PART, 16, 18)
      .noDuplicateTokens()
  }


  @Test
  fun `segments of the filepath are emitted`() {
    tokenizing(indexingAnalyzer, "foo/bar/Readme.md",FILE_RELATIVE_PATH )
      .print()
      .producesSameTokensAs(FilePathAnalyzer())
      .producesToken("foo", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("bar", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("Readme.md", TOKEN_TYPE_PATH_SEGMENT)

    tokenizing(indexingAnalyzer, "test/providers/files/FileIndexingAnalyzerTest.kt",FILE_RELATIVE_PATH )
      .print()
      .producesToken("test", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("providers", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("files", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("FileIndexingAnalyzerTest.kt", TOKEN_TYPE_PATH_SEGMENT)

  }

  @Test
  fun `filetypes are emitted as tokens`() {
    tokenizing(indexingAnalyzer, "java", FILE_TYPE)
      .print()
      .producesSameTokensAs(FileTypeAnalyzer())
      .producesToken("java", TOKEN_TYPE_FILETYPE)

    tokenizing(indexingAnalyzer, "md", FILE_TYPE)
      .print()
      .producesSameTokensAs(FileTypeAnalyzer())
      .producesToken("md", TOKEN_TYPE_FILETYPE)

    tokenizing(indexingAnalyzer, "kt", FILE_TYPE)
      .print()
      .producesSameTokensAs(FileTypeAnalyzer())
      .producesToken("kt", TOKEN_TYPE_FILETYPE)

    tokenizing(indexingAnalyzer, "gitignore", FILE_TYPE)
      .print()
      .producesSameTokensAs(FileTypeAnalyzer())
      .producesToken("gitignore", TOKEN_TYPE_FILETYPE)
  }
}
