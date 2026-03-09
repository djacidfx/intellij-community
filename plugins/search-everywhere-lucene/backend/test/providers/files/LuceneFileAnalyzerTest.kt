package com.intellij.searchEverywhereLucene.backend.providers.files

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LuceneAnalyzerTest {

  @Test
  fun testFileSearchAnalyzer() {
    tokenizing(FileSearchAnalyzer(), "SearchEveryWhereUI.java")
      .print()
      .producesToken("SearchEveryWhereUI.java", Path)
      .producesToken("java", Filetype, 19, 23)
      .producesToken("searcheverywhereui", Filename, 0, 18)
      .producesToken("search", FilenamePart, 0, 6)
      .producesToken("every", FilenamePart, 6, 11)
      .producesToken("sewui", FileNameAbbreviation, 0, 18)
      .producesToken("ui", FilenamePart, 16, 18)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), "com/intellij/MyFile.kt")
      .producesToken("kt", Filetype)
      .producesToken("com/intellij/MyFile.kt", Path)
      .producesToken("com", PathSegment)
      .producesToken("intellij", PathSegment)
      .producesToken("MyFile.kt", PathSegment)
      .producesToken("myfile",Filename)
      .producesToken("my", FilenamePart)
      .producesToken("file", FilenamePart)
      .print()
      .noDuplicateTokens()
  }

  @Test
  fun `test FileSearchAnalyzer hidden files`() {
    tokenizing(FileSearchAnalyzer(), ".gitignore")
      .print()
      .producesToken(".gitignore", Path)
      .producesToken("gitignore", Filetype)
      .noDuplicateTokens()

    tokenizing(FileSearchAnalyzer(), ".git/test")
      .print()
      .producesToken("test", Filename)
      .producesToken(".git/test", Path)
      
    tokenizing(FileSearchAnalyzer(), ".hidden").print()
      .producesToken("hidden", Filetype)
      .producesToken(".hidden", Path)
  }

  @Test
  fun `test FileSearchAnalyzer incomplete`() {
    tokenizing(FileSearchAnalyzer(), "Rea")
      .print()
      .producesToken("rea", FilenamePart)
      .noDuplicateTokens()


    tokenizing(FileSearchAnalyzer(), "SearchEver")
      .print()
      .producesToken("searchever", Filename, 0, 10)
      .producesToken("search", FilenamePart, 0, 6)
      .producesToken("ever", FilenamePart, 6, 10)
      .producesToken("se", FileNameAbbreviation, 0, 10)
      .noDuplicateTokens()
  }


  @Test
  fun `test FileSearchAnalyzer with Spaces`() {
    tokenizing(FileSearchAnalyzer(), "SearchEveryWhereUI.java com/intellij/Test.txt")
      .print()
      .producesToken("SearchEveryWhereUI.java", Path)
      .producesToken("java", Filetype, 19, 23)
      .producesToken("search", FilenamePart, 0, 6)
      .producesToken("every", FilenamePart, 6, 11)
      .producesToken("sewui", FileNameAbbreviation, 0, 18)
      .producesToken("ui", FilenamePart, 16, 18)
      .producesToken("com/intellij/Test.txt", Path)
      .producesToken("com", PathSegment)
      .producesToken("intellij", PathSegment)
      .producesToken("Test.txt", PathSegment)
      .producesToken("test", Filename)
      .producesToken("txt", Filetype)
      .noDuplicateTokens()
  }

  @Test
  fun `test FileSearchAnalyzer word index`() {
    tokenizing(FileSearchAnalyzer(), "Readme foo")
      .print()
      .producesTokenWithWordIndex("readme", Filename, 0)
      .producesTokenWithWordIndex("foo", Filename, 1)
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

  private val Filename = FileIndex.TOKEN_TYPE_FILENAME
  private val FilenamePart = FileIndex.TOKEN_TYPE_FILENAME_PART
  private val FileNameAbbreviation = FileIndex.TOKEN_TYPE_FILENAME_ABBREVIATION
  private val Path = FileIndex.TOKEN_TYPE_PATH
  private val PathSegment = FileIndex.TOKEN_TYPE_PATH_SEGMENT
  private val Filetype = FileIndex.TOKEN_TYPE_FILETYPE
}
