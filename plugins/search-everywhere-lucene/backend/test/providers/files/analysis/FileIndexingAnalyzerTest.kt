package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import com.intellij.searchEverywhereLucene.backend.AnalyzersTestBase
import com.intellij.searchEverywhereLucene.backend.providers.files.FileIndex.Companion.FILE_RELATIVE_PATH
import com.intellij.searchEverywhereLucene.backend.providers.files.FileIndex.Companion.FILE_TYPE
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

    // The file Name Analyzer should produce the same tokens given a filename as the FileSearchAnalyzer.
    tokenizing(indexingAnalyzer, "SearchEveryWhereUI.java")
      .print()
      .noDuplicateTokens()
      .producesSameTokensAs(FileNameAnalyzer())
      .producesToken("SearchEveryWhereUI.java", FileTokenType.PATH.type)
      .producesToken("java", FileTokenType.FILETYPE.type, 19, 23)
      .producesToken("searcheverywhereui", FileTokenType.FILENAME.type, 0, 18)
      .producesToken("search", FileTokenType.FILENAME_PART.type, 0, 6)
      .producesToken("every", FileTokenType.FILENAME_PART.type, 6, 11)
      .producesToken("where", FileTokenType.FILENAME_PART.type, 11, 16)
      .producesToken("sewu", FileTokenType.FILENAME_ABBREVIATION.type, 0, 18)
      .producesToken("ui", FileTokenType.FILENAME_PART.type, 16, 18)
      .noDuplicateTokens()
  }


  @Test
  fun `segments of the filepath are emitted`() {
    tokenizing(indexingAnalyzer, "foo/bar/Readme.md",FILE_RELATIVE_PATH )
      .print()
      .producesSameTokensAs(FilePathAnalyzer())
      .producesToken("foo", FileTokenType.PATH_SEGMENT.type)
      .producesToken("bar", FileTokenType.PATH_SEGMENT.type)
      .producesToken("Readme.md", FileTokenType.PATH_SEGMENT.type)

    tokenizing(indexingAnalyzer, "test/providers/files/FileIndexingAnalyzerTest.kt",FILE_RELATIVE_PATH )
      .print()
      .producesToken("test", FileTokenType.PATH_SEGMENT.type)
      .producesToken("providers", FileTokenType.PATH_SEGMENT.type)
      .producesToken("files", FileTokenType.PATH_SEGMENT.type)
      .producesToken("FileIndexingAnalyzerTest.kt", FileTokenType.PATH_SEGMENT.type)

  }

  @Test
  fun `filetypes are emitted as tokens`() {
    tokenizing(indexingAnalyzer, "java", FILE_TYPE)
      .print()
      .producesSameTokensAs(FileTypeAnalyzer())
      .producesToken("java", FileTokenType.FILETYPE.type)

    tokenizing(indexingAnalyzer, "md", FILE_TYPE)
      .print()
      .producesSameTokensAs(FileTypeAnalyzer())
      .producesToken("md", FileTokenType.FILETYPE.type)

    tokenizing(indexingAnalyzer, "kt", FILE_TYPE)
      .print()
      .producesSameTokensAs(FileTypeAnalyzer())
      .producesToken("kt", FileTokenType.FILETYPE.type)

    tokenizing(indexingAnalyzer, "gitignore", FILE_TYPE)
      .print()
      .producesSameTokensAs(FileTypeAnalyzer())
      .producesToken("gitignore", FileTokenType.FILETYPE.type)
  }
}
