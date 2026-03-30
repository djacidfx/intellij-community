package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import com.intellij.searchEverywhereLucene.backend.AnalyzersTestBase
import org.junit.jupiter.api.Test

/**
 * Tests for the FileSearchAnalyzer used for query analysis during search.
 */
class FileSearchingAnalyzerTest : AnalyzersTestBase() {

  @Test
  fun testFileSearchAnalyzer() {
    tokenizing(FileSearchAnalyzer(), "SearchEveryWhereUI.java")
      .print()
      .producesToken("SearchEveryWhereUI.java", FileTokenType.PATH)
      .producesToken("java", FileTokenType.FILETYPE, 19, 23)
      .producesToken("searcheverywhereui", FileTokenType.FILENAME, 0, 18)
      .producesToken("search", FileTokenType.FILENAME_PART, 0, 6)
      .producesToken("every", FileTokenType.FILENAME_PART, 6, 11)
      .producesToken("sewu", FileTokenType.FILENAME_ABBREVIATION, 0, 18)
      .producesToken("ui", FileTokenType.FILENAME_PART, 16, 18)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "com/intellij/MyFile.kt")
      .producesToken("kt", FileTokenType.FILETYPE)
      .producesToken("com/intellij/MyFile.kt", FileTokenType.PATH)
      .producesToken("com", FileTokenType.PATH_SEGMENT)
      .producesToken("intellij", FileTokenType.PATH_SEGMENT)
      .producesToken("MyFile.kt", FileTokenType.PATH_SEGMENT)
      .producesToken("myfile", FileTokenType.FILENAME)
      .producesToken("my", FileTokenType.FILENAME_PART)
      .producesToken("file", FileTokenType.FILENAME_PART)
      .print()
      .noDuplicateTokens()
  }

  @Test
  fun `test FileSearchAnalyzer hidden files`() {
    tokenizing(FileSearchAnalyzer(), ".gitignore")
      .print()
      .producesToken(".gitignore", FileTokenType.PATH)
      .producesToken("gitignore", FileTokenType.FILETYPE)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), ".git/test")
      .print()
      .producesToken("test", FileTokenType.FILENAME)
      .producesToken(".git/test", FileTokenType.PATH)
      
    tokenizing(FileSearchAnalyzer(), ".hidden").print()
      .producesToken("hidden", FileTokenType.FILETYPE)
      .producesToken(".hidden", FileTokenType.PATH)
  }

  @Test
  fun `test FileSearchAnalyzer incomplete`() {
    tokenizing(FileSearchAnalyzer(), "Rea")
      .print()
      .producesToken("rea", FileTokenType.FILENAME_PART)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "java")
      .print()
      .producesToken("java", FileTokenType.FILENAME_PART)
      .producesToken("java", FileTokenType.FILETYPE)
      .producesToken("java", FileTokenType.FILENAME)
      .producesToken("java", FileTokenType.PATH_SEGMENT)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "kt")
      .print()
      .producesToken("kt", FileTokenType.FILENAME_PART)
      .producesToken("kt", FileTokenType.FILETYPE)
      .producesToken("kt", FileTokenType.FILENAME)
      .producesToken("kt", FileTokenType.PATH_SEGMENT)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "SearchEver")
      .print()
      .producesToken("searchever", FileTokenType.FILENAME, 0, 10)
      .producesToken("search", FileTokenType.FILENAME_PART, 0, 6)
      .producesToken("ever", FileTokenType.FILENAME_PART, 6, 10)
      .producesToken("se", FileTokenType.FILENAME_ABBREVIATION, 0, 10)
      .noDuplicateTokens()
  }


  @Test
  fun `test FileSearchAnalyzer with Spaces`() {
    tokenizing(FileSearchAnalyzer(), "SearchEveryWhereUI.java com/intellij/Test.txt")
      .print()
      .producesToken("SearchEveryWhereUI.java", FileTokenType.PATH)
      .producesToken("java", FileTokenType.FILETYPE, 19, 23)
      .producesToken("search", FileTokenType.FILENAME_PART, 0, 6)
      .producesToken("every", FileTokenType.FILENAME_PART, 6, 11)
      .producesToken("sewu", FileTokenType.FILENAME_ABBREVIATION, 0, 18)
      .producesToken("ui", FileTokenType.FILENAME_PART, 16, 18)
      .producesToken("com/intellij/Test.txt", FileTokenType.PATH)
      .producesToken("com", FileTokenType.PATH_SEGMENT)
      .producesToken("intellij", FileTokenType.PATH_SEGMENT)
      .producesToken("Test.txt", FileTokenType.PATH_SEGMENT)
      .producesToken("test", FileTokenType.FILENAME)
      .producesToken("txt", FileTokenType.FILETYPE)
      .noDuplicateTokens()
  }

  @Test
  fun `test FileSearchAnalyzer word index`() {
    tokenizing(FileSearchAnalyzer(), "Readme foo")
      .print()
      .producesTokenWithWordIndex("readme", FileTokenType.FILENAME, 0)
      .producesTokenWithWordIndex("foo", FileTokenType.FILENAME, 1)
  }

  @Test
  fun `produces filenameAbbreviation tokens`() {
    tokenizing(FileSearchAnalyzer(), "sec")
      .print()
      .producesToken("sec", FileTokenType.FILENAME_ABBREVIATION)
  }

  @Test
  fun `produces NO filenameAbbreviation with Skip tokens`() {
    tokenizing(FileSearchAnalyzer(), "sec")
      .print()
      .producesNoTokenThat {it.types.contains(FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS)}

  }


  @Test
  fun `IJPL-220105 ngrams filename parts`() {
    tokenizing(FileSearchAnalyzer(), "clag")
      .print()
      .producesToken("cl", FileTokenType.FILENAME_PART)
      .producesToken("cl", FileTokenType.PATH_SEGMENT_PREFIX)
      .producesToken("ag", FileTokenType.FILENAME_PART)
      .producesToken("ag", FileTokenType.PATH_SEGMENT_PREFIX)
  }
}
