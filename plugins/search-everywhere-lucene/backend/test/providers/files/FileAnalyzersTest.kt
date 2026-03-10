package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileSearchAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME_ABBREVIATION
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME_PART
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILETYPE
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_PATH
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_PATH_SEGMENT
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.WordAttribute
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

//TODO create a new ManyTypes Attribute which stores types as Set<String>. This is to ensure we dont query with duplicate tokens in the query.
class FileAnalyzerTests {

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

  private fun tokenizing(analyzer: Analyzer, text: String): TokenAssertion {
    val tokens = mutableListOf<TokenInfo>()
    val tokenStream = analyzer.tokenStream("content", text)
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

  private data class TokenInfo(val term: String, val type: String, val startOffset: Int, val endOffset: Int, val wordIndex: Int)

  private class TokenAssertion(private val analyzer: Analyzer, private val text: String, private val tokens: List<TokenInfo>) {
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

  private val FILE_NAME = TOKEN_TYPE_FILENAME
  private val FILE_NAME_PART = TOKEN_TYPE_FILENAME_PART
  private val FILE_NAME_ABBREVIATION = TOKEN_TYPE_FILENAME_ABBREVIATION
  private val PATH = TOKEN_TYPE_PATH
  private val PATH_SEGMENT = TOKEN_TYPE_PATH_SEGMENT
  private val FILETYPE = TOKEN_TYPE_FILETYPE
}
