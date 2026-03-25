package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import com.intellij.searchEverywhereLucene.backend.AnalyzersTestBase
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.KeywordTokenizer
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [AbbreviationTokenFilter] in isolation, paired with [WordSplittingTokenFilter].
 *
 * Pipeline under test:
 *   KeywordTokenizer
 *   -> TypeSettingFilter(FILENAME)
 *   -> WordSplittingTokenFilter(FILENAME->FILENAME_PART, PassthroughLast)
 *   -> AbbreviationTokenFilter(FILENAME_PART->FILENAME_ABBREVIATION)
 */
class AbbreviationTokenFilterTest : AnalyzersTestBase() {

  private fun abbreviationAnalyzer(): Analyzer = object : Analyzer() {
    override fun createComponents(fieldName: String): TokenStreamComponents {
      val tokenizer = KeywordTokenizer()
      var stream: TokenStream = tokenizer
      // Tag the raw token as FILENAME so downstream filters recognise it
      stream = object : org.apache.lucene.analysis.TokenFilter(stream) {
        private val multiTypeAttr = addAttribute(MultiTypeAttribute::class.java)
        override fun incrementToken(): Boolean {
          if (!input.incrementToken()) return false
          multiTypeAttr.clearTypes().setTypes(setOf(FileTokenType.FILENAME))
          return true
        }
      }
      stream = WordSplittingTokenFilter(stream,
        inputTypes = setOf(FileTokenType.FILENAME),
        outputType = FileTokenType.FILENAME_PART,
        passThrough = PassthroughOptions.PassthroughLast)
      stream = AbbreviationTokenFilter(stream,
        sourceTypes = setOf(FileTokenType.FILENAME_PART),
        outputType = FileTokenType.FILENAME_ABBREVIATION,
        skipOutputType = FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS)
      return TokenStreamComponents(tokenizer, stream)
    }
  }

  @Test
  fun `abbreviation derived from camelCase parts`() {
    // SearchEveryWhereUI -> Search+Every+Where+UI -> s+e+w+ui = "sewui"
    // SearchEveryWhereUI -> Search+Every+Where+UI -> s+e+w+u = "sewu"
    tokenizing(abbreviationAnalyzer(), "SearchEveryWhereUI")
      .producesToken("sewu", FileTokenType.FILENAME_ABBREVIATION.type)
      .producesToken("search", FileTokenType.FILENAME_PART.type, 0, 6)
      .producesToken("every", FileTokenType.FILENAME_PART.type, 6, 11)
      .producesToken("where", FileTokenType.FILENAME_PART.type, 11, 16)
      .producesToken("ui", FileTokenType.FILENAME_PART.type, 16, 18)
      .producesToken("searcheverywhereui", FileTokenType.FILENAME.type, 0, 18)
      .noDuplicateTokens()
  }

  @Test
  fun `no abbreviation for single-part lowercase word`() {
    // "readme" -> single part -> abbreviation "r" (len 1) -> skipped
    tokenizing(abbreviationAnalyzer(), "readme")
      .producesToken("readme", FileTokenType.FILENAME_PART.type)
      .producesToken("readme", FileTokenType.FILENAME.type)
      // abbreviation "r" should NOT be present
      .noDuplicateTokens()
    val types = captureTokenTypes(abbreviationAnalyzer(), "readme")
    assertTrue(types.none { it == FileTokenType.FILENAME_ABBREVIATION.type }, "No abbreviation expected for single-char abbrev")
  }

  @Test
  fun `abbreviation for two-part name`() {
    tokenizing(abbreviationAnalyzer(), "MyFile")
      .producesToken("mf", FileTokenType.FILENAME_ABBREVIATION.type)
      .producesToken("my", FileTokenType.FILENAME_PART.type, 0, 2)
      .producesToken("file", FileTokenType.FILENAME_PART.type, 2, 6)
      .noDuplicateTokens()
  }

  @Test
  fun `multiple abbreviations with allowedSkip`() {
    // SearchEveryWhereUI -> [Search, Every, Where, UI], allowedSkip=1, minLength=2
    // size=4: "sewu"; size=3: "sew"(skip UI), "seu"(skip Where), "swu"(skip Every), "ewu"(skip Search)
    tokenizing(abbreviationAnalyzerWithSkip(1), "SearchEveryWhereUI")
      .producesToken("sewu", FileTokenType.FILENAME_ABBREVIATION.type)
      .producesToken("sew", FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS.type)
      .producesToken("seu", FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS.type)
      .producesToken("swu", FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS.type)
      .producesToken("ewu", FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS.type)
      .noDuplicateTokens()
  }

  private fun abbreviationAnalyzerWithSkip(allowedSkip: Int): Analyzer = object : Analyzer() {
    override fun createComponents(fieldName: String): TokenStreamComponents {
      val tokenizer = KeywordTokenizer()
      var stream: TokenStream = tokenizer
      stream = object : org.apache.lucene.analysis.TokenFilter(stream) {
        private val multiTypeAttr = addAttribute(MultiTypeAttribute::class.java)
        override fun incrementToken(): Boolean {
          if (!input.incrementToken()) return false
          multiTypeAttr.clearTypes().setTypes(setOf(FileTokenType.FILENAME))
          return true
        }
      }
      stream = WordSplittingTokenFilter(stream,
        inputTypes = setOf(FileTokenType.FILENAME),
        outputType = FileTokenType.FILENAME_PART,
        passThrough = PassthroughOptions.PassthroughLast)
      stream = AbbreviationTokenFilter(stream,
        sourceTypes = setOf(FileTokenType.FILENAME_PART),
        outputType = FileTokenType.FILENAME_ABBREVIATION,
        allowedSkip = allowedSkip,
        skipOutputType = FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS)
      return TokenStreamComponents(tokenizer, stream)
    }
  }

  private fun captureTokenTypes(analyzer: Analyzer, text: String): List<String> {
    val tokenStream = analyzer.tokenStream("content", text)
    val multiTypeAttr = tokenStream.addAttribute(MultiTypeAttribute::class.java)
    tokenStream.reset()
    val types = mutableListOf<String>()
    while (tokenStream.incrementToken()) {
      multiTypeAttr.activeTypes().forEach { types.add(it.type) }
    }
    tokenStream.end()
    tokenStream.close()
    return types
  }
}