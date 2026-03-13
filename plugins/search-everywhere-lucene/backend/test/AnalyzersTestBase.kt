package com.intellij.searchEverywhereLucene.backend

import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.WordAttribute
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Base class for analyzer tests providing common testing infrastructure.
 */
abstract class AnalyzersTestBase {

  protected fun tokenizing(analyzer: Analyzer, text: String, fieldName: String = "content"): TokenAssertion {
    val tokens = mutableListOf<TokenInfo>()
    val tokenStream = analyzer.tokenStream(fieldName, text)
    val termAttr = tokenStream.addAttribute(CharTermAttribute::class.java)
    val typeAttr = tokenStream.addAttribute(TypeAttribute::class.java)
    val offsetAttr = tokenStream.addAttribute(OffsetAttribute::class.java)
    val wordAttr = tokenStream.addAttribute(WordAttribute::class.java)

    tokenStream.reset()
    while (tokenStream.incrementToken()) {
      tokens.add(TokenInfo(termAttr.toString(), typeAttr.type(), offsetAttr.startOffset(), offsetAttr.endOffset(), wordAttr.wordIndex))
    }
    tokenStream.end()
    tokenStream.close()
    return TokenAssertion(analyzer, text, tokens)
  }

  protected data class TokenInfo(val term: String, val type: String, val startOffset: Int, val endOffset: Int, val wordIndex: Int)

  protected class TokenAssertion(private val analyzer: Analyzer, private val text: String, private val tokens: List<TokenInfo>) {
    fun producesToken(term: String, type: String, startOffset: Int? = null, endOffset: Int? = null): TokenAssertion {
      val found = tokens.any {
        it.term == term && it.type == type &&
        (startOffset == null || it.startOffset == startOffset) &&
        (endOffset == null || it.endOffset == endOffset)
      }
      val offsetMsg = if (startOffset != null || endOffset != null) " with offsets [$startOffset-$endOffset]" else ""
      assertTrue(found, "Token with term \"$term\" and type \"$type\"$offsetMsg not found in $tokens")
      return this
    }

    fun producesTokenWithWordIndex(term: String, type: String, wordIndex: Int): TokenAssertion {
      val found = tokens.any {
        it.term == term && it.type == type && it.wordIndex == wordIndex
      }
      assertTrue(found, "Token with term \"$term\", type \"$type\" and wordIndex $wordIndex not found in $tokens")
      return this
    }

    fun print(): TokenAssertion {
      println("\nAnalyzer: ${analyzer::class.simpleName}")
      println("Text: \"$text\"")
      val tokenStream = analyzer.tokenStream("content", text)
      val termAttr = tokenStream.addAttribute(CharTermAttribute::class.java)
      val posIncrAttr = tokenStream.addAttribute(PositionIncrementAttribute::class.java)
      val offsetAttr = tokenStream.addAttribute(OffsetAttribute::class.java)
      val typeAttr = tokenStream.addAttribute(TypeAttribute::class.java)
      val wordAttr = tokenStream.addAttribute(WordAttribute::class.java)

      tokenStream.reset()
      var position = 0
      while (tokenStream.incrementToken()) {
        position += posIncrAttr.positionIncrement
        println(String.format("  pos %2d: %-25s offset [%2d-%2d] word: %d type: %s",
                              position,
                              termAttr.toString(),
                              offsetAttr.startOffset(),
                              offsetAttr.endOffset(),
                              wordAttr.wordIndex,
                              typeAttr.type()))
      }
      tokenStream.end()
      tokenStream.close()
      return this
    }

    fun noDuplicateTokens(): TokenAssertion {
      val seen = mutableSetOf<TokenInfo>()
      for (token in tokens) {
        assertTrue(seen.add(token), "Duplicate token found: $token")
      }
      return this
    }
  }
}
