package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import com.intellij.searchEverywhereLucene.backend.AnalyzersTestBase
import org.junit.jupiter.api.Test

/**
 * Tests for the individual indexing analyzers used to build per-type document fields.
 */
class FileIndexingAnalyzerTest : AnalyzersTestBase() {

  @Test
  fun `test FileNameAnalyzer`() {
    tokenizing(FileNameAnalyzer(), "SearchEveryWhereUI.java")
      .print()
      .noDuplicateTokens()
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
  fun `test FileNameAnalyzer emits skip abbreviations`() {
    // SearchEveryWhereUI has 4 camel parts; with allowedSkip=1 the filter emits skip variants
    tokenizing(FileNameAnalyzer(), "SearchEveryWhereUI.java")
      .producesToken("sew", FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS.type, 0, 18)
      .producesToken("seu", FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS.type, 0, 18)
      .producesToken("swu", FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS.type, 0, 18)
      .producesToken("ewu", FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS.type, 0, 18)
  }

  @Test
  fun `segments of the filepath are emitted`() {
    tokenizing(FilePathAnalyzer(), "foo/bar/Readme.md")
      .print()
      .producesToken("foo", FileTokenType.PATH_SEGMENT.type)
      .producesToken("bar", FileTokenType.PATH_SEGMENT.type)
      .producesToken("Readme.md", FileTokenType.PATH_SEGMENT.type)

    tokenizing(FilePathAnalyzer(), "test/providers/files/FileIndexingAnalyzerTest.kt")
      .print()
      .producesToken("test", FileTokenType.PATH_SEGMENT.type)
      .producesToken("providers", FileTokenType.PATH_SEGMENT.type)
      .producesToken("files", FileTokenType.PATH_SEGMENT.type)
      .producesToken("FileIndexingAnalyzerTest.kt", FileTokenType.PATH_SEGMENT.type)
  }

  @Test
  fun `filetypes are emitted as tokens`() {
    tokenizing(FileTypeAnalyzer(), "java")
      .print()
      .producesToken("java", FileTokenType.FILETYPE.type)

    tokenizing(FileTypeAnalyzer(), "md")
      .print()
      .producesToken("md", FileTokenType.FILETYPE.type)

    tokenizing(FileTypeAnalyzer(), "kt")
      .print()
      .producesToken("kt", FileTokenType.FILETYPE.type)

    tokenizing(FileTypeAnalyzer(), "gitignore")
      .print()
      .producesToken("gitignore", FileTokenType.FILETYPE.type)
  }
}
