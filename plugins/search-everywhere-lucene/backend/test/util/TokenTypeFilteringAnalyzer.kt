package com.intellij.searchEverywhereLucene.backend.util

import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.AbbreviationTokenFilter
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileTokenType
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FilenameNgramFilter
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.MultiTypeAttribute
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.PassthroughOptions
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.PositionIncrementFromOffsetFilter
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.SearchPathTypeFilter
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TokenMergingFilter
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.WordAttribute
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.WordIndexFilter
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.WordSplittingTokenFilter
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.WhitespaceTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute

/**
 * A flexible token filter that allows filtering tokens based on various predicates.
 * Supports filtering by type, word index, offset, and term.
 */
class TokenAttributeFilter(
  input: TokenStream,
  private val typePredicate: ((String) -> Boolean)? = null,
  private val wordIndexPredicate: ((Int) -> Boolean)? = null,
  private val offsetPredicate: ((Int, Int) -> Boolean)? = null,
  private val termPredicate: ((String) -> Boolean)? = null,
) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val offsetAttr = addAttribute(OffsetAttribute::class.java)
  private val wordAttr = addAttribute(WordAttribute::class.java)
  private val multiTypeAttr = addAttribute(MultiTypeAttribute::class.java)

  override fun incrementToken(): Boolean {
    while (input.incrementToken()) {
      val wordIndex = wordAttr.wordIndex
      val startOffset = offsetAttr.startOffset()
      val endOffset = offsetAttr.endOffset()
      val term = termAttr.toString()

      if (typePredicate != null) {
        val effectiveTypes = multiTypeAttr.activeTypes().map { it.type }
        if (effectiveTypes.none { typePredicate.invoke(it) }) continue
      }
      if (wordIndexPredicate != null && !wordIndexPredicate.invoke(wordIndex)) continue
      if (offsetPredicate != null && !offsetPredicate.invoke(startOffset, endOffset)) continue
      if (termPredicate != null && !termPredicate.invoke(term)) continue

      // All predicates passed
      return true
    }
    return false
  }
}

/**
 * Analyzer that wraps FileSearchAnalyzer and filters tokens by type.
 * This is used for testing to ensure each token type can properly find results.
 */
internal class TokenTypeFilteringAnalyzer(
  @Suppress("unused") private val wrapped: Analyzer,
  private val tokenTypeWhitelist: List<String>,
) : Analyzer() {

  override fun createComponents(fieldName: String): TokenStreamComponents {
    val tokenizer = WhitespaceTokenizer()
    var stream: TokenStream = SearchPathTypeFilter(tokenizer)
    stream =
      WordSplittingTokenFilter(stream, setOf(FileTokenType.FILENAME), FileTokenType.FILENAME_PART, PassthroughOptions.PassthroughLast)
    stream = AbbreviationTokenFilter(
      stream,
      sourceTypes = setOf(FileTokenType.FILENAME_PART),
      outputType = FileTokenType.FILENAME_ABBREVIATION,
      allowedSkip = 1,
      skipOutputType = FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS,
      passThrough = PassthroughOptions.PassthroughLast,
    )
    stream = FilenameNgramFilter(stream)
    stream = TokenMergingFilter(stream)
    stream = PositionIncrementFromOffsetFilter(stream)
    stream = WordIndexFilter(stream)
    val typeFilter = TokenAttributeFilter(stream, typePredicate = { it in tokenTypeWhitelist })
    return TokenStreamComponents(tokenizer, typeFilter)
  }
}
