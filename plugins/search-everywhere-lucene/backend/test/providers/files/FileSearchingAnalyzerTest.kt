package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileSearchAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME_ABBREVIATION
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME_PART
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILETYPE
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_PATH
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_PATH_SEGMENT
import org.junit.jupiter.api.Test

/**
 * Tests for the FileSearchAnalyzer used for query analysis during search.
 */
class FileSearchingAnalyzerTest : AnalyzersTestBase() {

  @Test
  fun testFileSearchAnalyzer() {
    tokenizing(FileSearchAnalyzer(), "SearchEveryWhereUI.java")
      .print()
      .producesToken("SearchEveryWhereUI.java", TOKEN_TYPE_PATH)
      .producesToken("java", TOKEN_TYPE_FILETYPE, 19, 23)
      .producesToken("searcheverywhereui", TOKEN_TYPE_FILENAME, 0, 18)
      .producesToken("search", TOKEN_TYPE_FILENAME_PART, 0, 6)
      .producesToken("every", TOKEN_TYPE_FILENAME_PART, 6, 11)
      .producesToken("sewui", TOKEN_TYPE_FILENAME_ABBREVIATION, 0, 18)
      .producesToken("ui", TOKEN_TYPE_FILENAME_PART, 16, 18)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "com/intellij/MyFile.kt")
      .producesToken("kt", TOKEN_TYPE_FILETYPE)
      .producesToken("com/intellij/MyFile.kt", TOKEN_TYPE_PATH)
      .producesToken("com", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("intellij", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("MyFile.kt", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("myfile", TOKEN_TYPE_FILENAME)
      .producesToken("my", TOKEN_TYPE_FILENAME_PART)
      .producesToken("file", TOKEN_TYPE_FILENAME_PART)
      .print()
      .noDuplicateTokens()
  }

  @Test
  fun `test FileSearchAnalyzer hidden files`() {
    tokenizing(FileSearchAnalyzer(), ".gitignore")
      .print()
      .producesToken(".gitignore", TOKEN_TYPE_PATH)
      .producesToken("gitignore", TOKEN_TYPE_FILETYPE)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), ".git/test")
      .print()
      .producesToken("test", TOKEN_TYPE_FILENAME)
      .producesToken(".git/test", TOKEN_TYPE_PATH)
      
    tokenizing(FileSearchAnalyzer(), ".hidden").print()
      .producesToken("hidden", TOKEN_TYPE_FILETYPE)
      .producesToken(".hidden", TOKEN_TYPE_PATH)
  }

  @Test
  fun `test FileSearchAnalyzer incomplete`() {
    tokenizing(FileSearchAnalyzer(), "Rea")
      .print()
      .producesToken("rea", TOKEN_TYPE_FILENAME_PART)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "java")
      .print()
      .producesToken("java", TOKEN_TYPE_FILENAME_PART)
      .producesToken("java", TOKEN_TYPE_FILETYPE)
      .producesToken("java", TOKEN_TYPE_FILENAME)
      .producesToken("java", TOKEN_TYPE_PATH_SEGMENT)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "kt")
      .print()
      .producesToken("kt", TOKEN_TYPE_FILENAME_PART)
      .producesToken("kt", TOKEN_TYPE_FILETYPE)
      .producesToken("kt", TOKEN_TYPE_FILENAME)
      .producesToken("kt", TOKEN_TYPE_PATH_SEGMENT)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "SearchEver")
      .print()
      .producesToken("searchever", TOKEN_TYPE_FILENAME, 0, 10)
      .producesToken("search", TOKEN_TYPE_FILENAME_PART, 0, 6)
      .producesToken("ever", TOKEN_TYPE_FILENAME_PART, 6, 10)
      .producesToken("se", TOKEN_TYPE_FILENAME_ABBREVIATION, 0, 10)
      .noDuplicateTokens()
  }


  @Test
  fun `test FileSearchAnalyzer with Spaces`() {
    tokenizing(FileSearchAnalyzer(), "SearchEveryWhereUI.java com/intellij/Test.txt")
      .print()
      .producesToken("SearchEveryWhereUI.java", TOKEN_TYPE_PATH)
      .producesToken("java", TOKEN_TYPE_FILETYPE, 19, 23)
      .producesToken("search", TOKEN_TYPE_FILENAME_PART, 0, 6)
      .producesToken("every", TOKEN_TYPE_FILENAME_PART, 6, 11)
      .producesToken("sewui", TOKEN_TYPE_FILENAME_ABBREVIATION, 0, 18)
      .producesToken("ui", TOKEN_TYPE_FILENAME_PART, 16, 18)
      .producesToken("com/intellij/Test.txt", TOKEN_TYPE_PATH)
      .producesToken("com", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("intellij", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("Test.txt", TOKEN_TYPE_PATH_SEGMENT)
      .producesToken("test", TOKEN_TYPE_FILENAME)
      .producesToken("txt", TOKEN_TYPE_FILETYPE)
      .noDuplicateTokens()
  }

  @Test
  fun `test FileSearchAnalyzer word index`() {
    tokenizing(FileSearchAnalyzer(), "Readme foo")
      .print()
      .producesTokenWithWordIndex("readme", TOKEN_TYPE_FILENAME, 0)
      .producesTokenWithWordIndex("foo", TOKEN_TYPE_FILENAME, 1)
  }
}
