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
      .producesToken("SearchEveryWhereUI.java", PATH)
      .producesToken("java", FILETYPE, 19, 23)
      .producesToken("searcheverywhereui", FILE_NAME, 0, 18)
      .producesToken("search", FILE_NAME_PART, 0, 6)
      .producesToken("every", FILE_NAME_PART, 6, 11)
      .producesToken("sewui", FILE_NAME_ABBREVIATION, 0, 18)
      .producesToken("ui", FILE_NAME_PART, 16, 18)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "com/intellij/MyFile.kt")
      .producesToken("kt", FILETYPE)
      .producesToken("com/intellij/MyFile.kt", PATH)
      .producesToken("com", PATH_SEGMENT)
      .producesToken("intellij", PATH_SEGMENT)
      .producesToken("MyFile.kt", PATH_SEGMENT)
      .producesToken("myfile", FILE_NAME)
      .producesToken("my", FILE_NAME_PART)
      .producesToken("file", FILE_NAME_PART)
      .print()
      .noDuplicateTokens()
  }

  @Test
  fun `test FileSearchAnalyzer hidden files`() {
    tokenizing(FileSearchAnalyzer(), ".gitignore")
      .print()
      .producesToken(".gitignore", PATH)
      .producesToken("gitignore", FILETYPE)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), ".git/test")
      .print()
      .producesToken("test", FILE_NAME)
      .producesToken(".git/test", PATH)
      
    tokenizing(FileSearchAnalyzer(), ".hidden").print()
      .producesToken("hidden", FILETYPE)
      .producesToken(".hidden", PATH)
  }

  @Test
  fun `test FileSearchAnalyzer incomplete`() {
    tokenizing(FileSearchAnalyzer(), "Rea")
      .print()
      .producesToken("rea", FILE_NAME_PART)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "java")
      .print()
      .producesToken("java", FILE_NAME_PART)
      .producesToken("java", FILETYPE)
      .producesToken("java", FILE_NAME)
      .producesToken("java", PATH_SEGMENT)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "kt")
      .print()
      .producesToken("kt", FILE_NAME_PART)
      .producesToken("kt", FILETYPE)
      .producesToken("kt", FILE_NAME)
      .producesToken("java", PATH_SEGMENT)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "SearchEver")
      .print()
      .producesToken("searchever", FILE_NAME, 0, 10)
      .producesToken("search", FILE_NAME_PART, 0, 6)
      .producesToken("ever", FILE_NAME_PART, 6, 10)
      .producesToken("se", FILE_NAME_ABBREVIATION, 0, 10)
      .noDuplicateTokens()
  }


  @Test
  fun `test FileSearchAnalyzer with Spaces`() {
    tokenizing(FileSearchAnalyzer(), "SearchEveryWhereUI.java com/intellij/Test.txt")
      .print()
      .producesToken("SearchEveryWhereUI.java", PATH)
      .producesToken("java", FILETYPE, 19, 23)
      .producesToken("search", FILE_NAME_PART, 0, 6)
      .producesToken("every", FILE_NAME_PART, 6, 11)
      .producesToken("sewui", FILE_NAME_ABBREVIATION, 0, 18)
      .producesToken("ui", FILE_NAME_PART, 16, 18)
      .producesToken("com/intellij/Test.txt", PATH)
      .producesToken("com", PATH_SEGMENT)
      .producesToken("intellij", PATH_SEGMENT)
      .producesToken("Test.txt", PATH_SEGMENT)
      .producesToken("test", FILE_NAME)
      .producesToken("txt", FILETYPE)
      .noDuplicateTokens()
  }

  @Test
  fun `test FileSearchAnalyzer word index`() {
    tokenizing(FileSearchAnalyzer(), "Readme foo")
      .print()
      .producesTokenWithWordIndex("readme", FILE_NAME, 0)
      .producesTokenWithWordIndex("foo", FILE_NAME, 1)
  }


  private val FILE_NAME = TOKEN_TYPE_FILENAME
  private val FILE_NAME_PART = TOKEN_TYPE_FILENAME_PART
  private val FILE_NAME_ABBREVIATION = TOKEN_TYPE_FILENAME_ABBREVIATION
  private val PATH = TOKEN_TYPE_PATH
  private val PATH_SEGMENT = TOKEN_TYPE_PATH_SEGMENT
  private val FILETYPE = TOKEN_TYPE_FILETYPE
}
