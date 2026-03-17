package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.WhitespaceTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute
import org.apache.lucene.util.Attribute
import org.apache.lucene.util.AttributeImpl
import org.apache.lucene.util.AttributeReflector


class FileSearchAnalyzer : Analyzer() {
  override fun createComponents(fieldName: String): TokenStreamComponents {
    val tokenizer = WhitespaceTokenizer()
    val filter = FileSearchTokenFilter(tokenizer)
    return TokenStreamComponents(tokenizer, filter)
  }
}

class FileSearchTokenFilter(input: TokenStream) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val typeAttr = addAttribute(TypeAttribute::class.java)
  private val posIncrAttr = addAttribute(PositionIncrementAttribute::class.java)
  private val offsetAttr: OffsetAttribute = addAttribute(OffsetAttribute::class.java)
  private val wordAttr = addAttribute(WordAttribute::class.java)

  private val pendingTokens = mutableListOf<PendingToken>()

  private data class PendingToken(
    val term: String,
    val type: String,
    val posIncr: Int,
    val startOffset: Int,
    val endOffset: Int,
    val wordIndex: Int,
  )

  private var lastStartOffset = -1
  private var currentWordIndex = -1

  override fun incrementToken(): Boolean {
    while (true) {
      if (pendingTokens.isNotEmpty()) {
        val token = pendingTokens.removeAt(0)
        termAttr.setEmpty().append(token.term)
        typeAttr.setType(token.type)
        wordAttr.wordIndex = token.wordIndex

        if (lastStartOffset == -1) {
          posIncrAttr.positionIncrement = token.posIncr
        }
        else if (token.startOffset == lastStartOffset) {
          posIncrAttr.positionIncrement = 0
        }
        else {
          posIncrAttr.positionIncrement = 1
        }

        lastStartOffset = token.startOffset
        offsetAttr.setOffset(token.startOffset, token.endOffset)
        return true
      }

      if (!input.incrementToken()) return false

      currentWordIndex++
      val segment = termAttr.toString()
      val originalPosIncr = posIncrAttr.positionIncrement
      val originalStartOffset = offsetAttr.startOffset()

      val identified = identifyTokensWithOffsets(segment)

      var firstInSegment = true
      for (tokenInfo in identified) {
        val basePosIncr = if (firstInSegment) originalPosIncr else 1
        val absoluteTokenStart = originalStartOffset + tokenInfo.start
        val absoluteTokenEnd = originalStartOffset + tokenInfo.end

        if (tokenInfo.type == TOKEN_TYPE_FILENAME) {
          val subInfos = buildFilenameSubTokens(tokenInfo.text, absoluteTokenStart)
          var isFirst = true
          for (info in subInfos) {
            val posIncr = if (isFirst) basePosIncr else 0
            pendingTokens.add(PendingToken(info.term, info.type, posIncr, info.start, info.end, currentWordIndex))
            isFirst = false
          }
        }
        else {
          val termToAdd =
            if (tokenInfo.type == TOKEN_TYPE_PATH || tokenInfo.type == TOKEN_TYPE_PATH_SEGMENT) tokenInfo.text else tokenInfo.text.lowercase()
          pendingTokens.add(PendingToken(termToAdd, tokenInfo.type, basePosIncr, absoluteTokenStart, absoluteTokenEnd, currentWordIndex))
        }
        firstInSegment = false
      }
    }
  }

  override fun reset() {
    super.reset()
    pendingTokens.clear()
    lastStartOffset = -1
    currentWordIndex = -1
  }

  private data class IdentifiedToken(val text: String, val type: String, val start: Int, val end: Int)

  private fun identifyTokensWithOffsets(segment: String): List<IdentifiedToken> {
    val result = mutableListOf<IdentifiedToken>()
    var currentOffset = 0
    val parts = segment.split('/', '\\')

    // Always add the full segment as a PATH token first
    result.add(IdentifiedToken(segment, TOKEN_TYPE_PATH, 0, segment.length))

    for (i in 0 until parts.size - 1) {
      val part = parts[i]
      if (part.isNotEmpty()) {
        result.add(IdentifiedToken(part, TOKEN_TYPE_PATH_SEGMENT, currentOffset, currentOffset + part.length))
      }
      currentOffset += part.length + 1
    }

    val lastPart = parts.last()
    if (lastPart.isEmpty()) return result

    // If it's a multi-part path, the last part is also a PATH_SEGMENT token
    if (parts.size > 1) {
      result.add(IdentifiedToken(lastPart, TOKEN_TYPE_PATH_SEGMENT, currentOffset, currentOffset + lastPart.length))
    }

    val dotIndex = lastPart.lastIndexOf('.')
    if (dotIndex < 0) {
      result.add(IdentifiedToken(lastPart, TOKEN_TYPE_FILENAME, currentOffset, currentOffset + lastPart.length))
      // Also emit as FILETYPE since extension-less files like "java", "kt" should be searchable by type
      if (lastPart.isNotEmpty()) {
        result.add(IdentifiedToken(lastPart, TOKEN_TYPE_FILETYPE, currentOffset, currentOffset + lastPart.length))
      }
      // For single-component paths without extension (like "foo"), also emit as PATH_SEGMENT
      // so that searching for "foo" can match directory names in paths like "foo/bar/file.txt"
      if (parts.size == 1 && lastPart.isNotEmpty()) {
        result.add(IdentifiedToken(lastPart, TOKEN_TYPE_PATH_SEGMENT, currentOffset, currentOffset + lastPart.length))
      }
    }
    else if (dotIndex == 0) {
      // Hidden file like .gitignore
      result.add(IdentifiedToken(lastPart, TOKEN_TYPE_FILENAME, currentOffset, currentOffset + lastPart.length))
      val filetype = lastPart.substring(1)
      if (filetype.isNotEmpty()) {
        result.add(IdentifiedToken(filetype, TOKEN_TYPE_FILETYPE, currentOffset + 1, currentOffset + lastPart.length))
      }
    }
    else {
      val filename = lastPart.substring(0, dotIndex)
      val filetype = lastPart.substring(dotIndex + 1)
      result.add(IdentifiedToken(filename, TOKEN_TYPE_FILENAME, currentOffset, currentOffset + filename.length))
      if (filetype.isNotEmpty()) {
        result.add(IdentifiedToken(filetype, TOKEN_TYPE_FILETYPE, currentOffset + dotIndex + 1, currentOffset + lastPart.length))
      }
    }
    return result
  }
}


class InitialsTokenFilter(input: TokenStream) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val offsetAttr = addAttribute(OffsetAttribute::class.java)
  private val posIncrAttr = addAttribute(PositionIncrementAttribute::class.java)
  private val typeAttr = addAttribute(TypeAttribute::class.java)

  private val bufferedTokens = mutableListOf<TokenState>()
  private var abbreviationToken: TokenState? = null

  private data class TokenState(val term: String, val startOffset: Int, val endOffset: Int, val posIncr: Int, val type: String)

  private var lastStartOffset = -1

  private fun emitToken(state: TokenState) {
    termAttr.setEmpty().append(state.term)

    val currentStart = state.startOffset
    if (lastStartOffset == -1) {
      posIncrAttr.positionIncrement = state.posIncr
    }
    else if (currentStart == lastStartOffset) {
      posIncrAttr.positionIncrement = 0
    }
    else {
      posIncrAttr.positionIncrement = 1
    }
    lastStartOffset = currentStart

    offsetAttr.setOffset(state.startOffset, state.endOffset)
    typeAttr.setType(state.type)
  }

  override fun incrementToken(): Boolean {
    if (abbreviationToken != null) {
      val state = abbreviationToken!!
      emitToken(state)
      abbreviationToken = null
      return true
    }

    if (bufferedTokens.isNotEmpty()) {
      val state = bufferedTokens.removeAt(0)
      emitToken(state)
      return true
    }

    if (!input.incrementToken()) return false

    val firstToken =
      TokenState(termAttr.toString(), offsetAttr.startOffset(), offsetAttr.endOffset(), posIncrAttr.positionIncrement, typeAttr.type())
    val tokens = mutableListOf<TokenState>()
    tokens.add(firstToken)

    var minOffset = firstToken.startOffset
    var maxOffset = firstToken.endOffset

    while (input.incrementToken()) {
      val term = termAttr.toString()
      val start = offsetAttr.startOffset()
      val end = offsetAttr.endOffset()
      val posIncr = posIncrAttr.positionIncrement
      val type = typeAttr.type()

      tokens.add(TokenState(term, start, end, posIncr, type))
      minOffset = minOf(minOffset, start)
      maxOffset = maxOf(maxOffset, end)
    }

    // Identify tokens from which to take initials.
    // We want tokens that are "parts" and don't overlap with each other in a way that repeats initials.
    // WordDelimiterGraphFilter with PRESERVE_ORIGINAL and CATENATE_ALL produces overlapping tokens.
    // Tokens with posIncr > 0 are usually the non-overlapping sequence.

    val initialsBuilder = StringBuilder()
    for (token in tokens) {
      if (token.posIncr > 0 && token.term.isNotEmpty()) {
        initialsBuilder.append(token.term[0].lowercaseChar())
        for (j in 1 until token.term.length) {
          if (token.term[j].isUpperCase()) {
            initialsBuilder.append(token.term[j].lowercaseChar())
          }
        }
      }
    }

    val initials = initialsBuilder.toString().lowercase()
    if (initials.length > 1) {
      abbreviationToken = TokenState(initials, minOffset, maxOffset, 0, TOKEN_TYPE_FILENAME_ABBREVIATION)
    }

    // Return the first token
    val first = tokens.removeAt(0)
    emitToken(first)

    bufferedTokens.addAll(tokens)
    return true
  }

  override fun reset() {
    super.reset()
    bufferedTokens.clear()
    abbreviationToken = null
    lastStartOffset = -1
  }
}

interface WordAttribute : Attribute {
  var wordIndex: Int
}

@Suppress("unused")
class WordAttributeImpl : AttributeImpl(), WordAttribute {
  override var wordIndex: Int = 0
  override fun clear() {
    wordIndex = 0
  }

  override fun copyTo(target: AttributeImpl) {
    (target as WordAttribute).wordIndex = wordIndex
  }

  override fun reflectWith(reflector: AttributeReflector) {
    reflector.reflect(WordAttribute::class.java, "wordIndex", wordIndex)
  }
}
