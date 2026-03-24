package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.searchEverywhereLucene.backend.AnalyzersTestBase
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileNameAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileSearchAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileTokenType
import org.junit.jupiter.api.Test

/**
 * Tests for the FileSearchAnalyzer used for query analysis during search.
 */
class FileSearchingAnalyzerTest : AnalyzersTestBase() {

  @Test
  fun testFileSearchAnalyzer() {
    tokenizing(FileSearchAnalyzer(), "SearchEveryWhereUI.java")
      .print()
      .producesSameTokensAs(FileNameAnalyzer())
      .producesToken("SearchEveryWhereUI.java", FileTokenType.PATH.type)
      .producesToken("java", FileTokenType.FILETYPE.type, 19, 23)
      .producesToken("searcheverywhereui", FileTokenType.FILENAME.type, 0, 18)
      .producesToken("search", FileTokenType.FILENAME_PART.type, 0, 6)
      .producesToken("every", FileTokenType.FILENAME_PART.type, 6, 11)
      .producesToken("sewui", FileTokenType.FILENAME_ABBREVIATION.type, 0, 18)
      .producesToken("ui", FileTokenType.FILENAME_PART.type, 16, 18)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "com/intellij/MyFile.kt")
      .producesToken("kt", FileTokenType.FILETYPE.type)
      .producesToken("com/intellij/MyFile.kt", FileTokenType.PATH.type)
      .producesToken("com", FileTokenType.PATH_SEGMENT.type)
      .producesToken("intellij", FileTokenType.PATH_SEGMENT.type)
      .producesToken("MyFile.kt", FileTokenType.PATH_SEGMENT.type)
      .producesToken("myfile", FileTokenType.FILENAME.type)
      .producesToken("my", FileTokenType.FILENAME_PART.type)
      .producesToken("file", FileTokenType.FILENAME_PART.type)
      .print()
      .noDuplicateTokens()
  }

  @Test
  fun `test FileSearchAnalyzer hidden files`() {
    tokenizing(FileSearchAnalyzer(), ".gitignore")
      .print()
      .producesToken(".gitignore", FileTokenType.PATH.type)
      .producesToken("gitignore", FileTokenType.FILETYPE.type)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), ".git/test")
      .print()
      .producesToken("test", FileTokenType.FILENAME.type)
      .producesToken(".git/test", FileTokenType.PATH.type)
      
    tokenizing(FileSearchAnalyzer(), ".hidden").print()
      .producesToken("hidden", FileTokenType.FILETYPE.type)
      .producesToken(".hidden", FileTokenType.PATH.type)
  }

  @Test
  fun `test FileSearchAnalyzer incomplete`() {
    tokenizing(FileSearchAnalyzer(), "Rea")
      .print()
      .producesToken("rea", FileTokenType.FILENAME_PART.type)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "java")
      .print()
      .producesToken("java", FileTokenType.FILENAME_PART.type)
      .producesToken("java", FileTokenType.FILETYPE.type)
      .producesToken("java", FileTokenType.FILENAME.type)
      .producesToken("java", FileTokenType.PATH_SEGMENT.type)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "kt")
      .print()
      .producesToken("kt", FileTokenType.FILENAME_PART.type)
      .producesToken("kt", FileTokenType.FILETYPE.type)
      .producesToken("kt", FileTokenType.FILENAME.type)
      .producesToken("kt", FileTokenType.PATH_SEGMENT.type)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "SearchEver")
      .print()
      .producesToken("searchever", FileTokenType.FILENAME.type, 0, 10)
      .producesToken("search", FileTokenType.FILENAME_PART.type, 0, 6)
      .producesToken("ever", FileTokenType.FILENAME_PART.type, 6, 10)
      .producesToken("se", FileTokenType.FILENAME_ABBREVIATION.type, 0, 10)
      .noDuplicateTokens()
  }


  @Test
  fun `test FileSearchAnalyzer with Spaces`() {
    tokenizing(FileSearchAnalyzer(), "SearchEveryWhereUI.java com/intellij/Test.txt")
      .print()
      .producesToken("SearchEveryWhereUI.java", FileTokenType.PATH.type)
      .producesToken("java", FileTokenType.FILETYPE.type, 19, 23)
      .producesToken("search", FileTokenType.FILENAME_PART.type, 0, 6)
      .producesToken("every", FileTokenType.FILENAME_PART.type, 6, 11)
      .producesToken("sewui", FileTokenType.FILENAME_ABBREVIATION.type, 0, 18)
      .producesToken("ui", FileTokenType.FILENAME_PART.type, 16, 18)
      .producesToken("com/intellij/Test.txt", FileTokenType.PATH.type)
      .producesToken("com", FileTokenType.PATH_SEGMENT.type)
      .producesToken("intellij", FileTokenType.PATH_SEGMENT.type)
      .producesToken("Test.txt", FileTokenType.PATH_SEGMENT.type)
      .producesToken("test", FileTokenType.FILENAME.type)
      .producesToken("txt", FileTokenType.FILETYPE.type)
      .noDuplicateTokens()
  }

  @Test
  fun `test FileSearchAnalyzer word index`() {
    tokenizing(FileSearchAnalyzer(), "Readme foo")
      .print()
      .producesTokenWithWordIndex("readme", FileTokenType.FILENAME.type, 0)
      .producesTokenWithWordIndex("foo", FileTokenType.FILENAME.type, 1)
  }

  @Test
  fun `IJPL-220105 ngrams filename parts`() {
    tokenizing(FileSearchAnalyzer(), "clag")
      .print()
      .producesToken("cl", FileTokenType.FILENAME_PART.type)
      .producesToken("cl", FileTokenType.PATH_SEGMENT_PREFIX.type)
      .producesToken("ag", FileTokenType.FILENAME_PART.type)
      .producesToken("ag", FileTokenType.PATH_SEGMENT_PREFIX.type)
  }
}
