package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.KeywordTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute


internal data class PartInfo(val term: String, val type: String, val start: Int, val end: Int)

/**
 * Breaks a filename stem into camelCase / numeric-boundary parts with offsets relative to the stem start.
 */
internal fun breakFilenameIntoParts(text: String): List<PartInfo> {
  val result = mutableListOf<PartInfo>()
  if (text.isEmpty()) return result

  var start = 0
  for (i in 1 until text.length) {
    val prev = text[i - 1]
    val curr = text[i]
    if ((prev.isLowerCase() && curr.isUpperCase()) ||
        (prev.isUpperCase() && curr.isUpperCase() && (i + 1 < text.length && text[i + 1].isLowerCase())) ||
        (prev.isLetter() && curr.isDigit()) ||
        (prev.isDigit() && curr.isLetter())) {
      result.add(PartInfo(text.substring(start, i), TOKEN_TYPE_FILENAME_PART, start, i))
      start = i
    }
  }
  result.add(PartInfo(text.substring(start), TOKEN_TYPE_FILENAME_PART, start, text.length))
  return result
}

/**
 * Builds all filename sub-tokens for a given name stem, offsetting by [baseOffset].
 * Emits: FILENAME token, FILENAME_ABBREVIATION (if initials.length > 1), and FILENAME_PART tokens.
 * All returned offsets are absolute (baseOffset-relative).
 */
internal fun buildFilenameSubTokens(name: String, baseOffset: Int): List<PartInfo> {
  val result = mutableListOf<PartInfo>()
  result.add(PartInfo(name.lowercase(), TOKEN_TYPE_FILENAME, baseOffset, baseOffset + name.length))

  val parts = breakFilenameIntoParts(name)
  val initialsBuilder = StringBuilder()
  for (part in parts) {
    if (part.term.isNotEmpty()) {
      initialsBuilder.append(part.term[0].lowercaseChar())
      for (j in 1 until part.term.length) {
        if (part.term[j].isUpperCase()) initialsBuilder.append(part.term[j].lowercaseChar())
      }
    }
  }
  val initials = initialsBuilder.toString()
  if (initials.length > 1) {
    result.add(PartInfo(initials, TOKEN_TYPE_FILENAME_ABBREVIATION, baseOffset, baseOffset + name.length))
  }

  for (part in parts) {
    result.add(PartInfo(part.term.lowercase(), TOKEN_TYPE_FILENAME_PART, baseOffset + part.start, baseOffset + part.end))
  }
  return result
}


/**
 * Tokenizes a single filename (no path separators) into:
 *  - TOKEN_TYPE_PATH  — full original text (case-preserved)
 *  - TOKEN_TYPE_FILENAME + TOKEN_TYPE_FILENAME_ABBREVIATION + TOKEN_TYPE_FILENAME_PART — name stem analysis
 *  - TOKEN_TYPE_FILETYPE — extension (lowercase)
 *
 * Tokens are emitted in non-decreasing offset order as required by Lucene.
 */
internal class FileNameTokenFilter(input: TokenStream) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val typeAttr = addAttribute(TypeAttribute::class.java)
  private val offsetAttr = addAttribute(OffsetAttribute::class.java)
  private val posIncrAttr = addAttribute(PositionIncrementAttribute::class.java)

  private val pending = ArrayDeque<PartInfo>()
  private var lastStartOffset = -1

  override fun incrementToken(): Boolean {
    if (pending.isNotEmpty()) {
      emitPending(pending.removeFirst())
      return true
    }

    if (!input.incrementToken()) return false

    val fullText = termAttr.toString()
    lastStartOffset = -1

    // Full text as PATH token (case-preserved)
    pending.add(PartInfo(fullText, TOKEN_TYPE_PATH, 0, fullText.length))

    val dotIndex = fullText.lastIndexOf('.')
    val (nameStem, ext, nameStart, extStart) = when {
      dotIndex < 0  -> Quadruple(fullText, null, 0, -1)
      dotIndex == 0 -> Quadruple(fullText, fullText.substring(1), 0, 1)
      else          -> Quadruple(fullText.substring(0, dotIndex), fullText.substring(dotIndex + 1), 0, dotIndex + 1)
    }

    // Filename sub-tokens first (offsets at/near 0, must precede FILETYPE to stay non-decreasing)
    for (sub in buildFilenameSubTokens(nameStem, nameStart)) {
      pending.add(sub)
    }

    // Extension last (highest offset)
    if (!ext.isNullOrEmpty()) {
      pending.add(PartInfo(ext.lowercase(), TOKEN_TYPE_FILETYPE, extStart, extStart + ext.length))
    }

    emitPending(pending.removeFirst())
    return true
  }

  private fun emitPending(part: PartInfo) {
    termAttr.setEmpty().append(part.term)
    typeAttr.setType(part.type)
    offsetAttr.setOffset(part.start, part.end)
    posIncrAttr.positionIncrement = if (lastStartOffset == -1 || part.start != lastStartOffset) 1 else 0
    lastStartOffset = part.start
  }

  override fun reset() {
    super.reset()
    pending.clear()
    lastStartOffset = -1
  }

  private data class Quadruple(val name: String, val ext: String?, val nameStart: Int, val extStart: Int)
}


class FileNameAnalyzer : Analyzer() {
  override fun createComponents(fieldName: String): TokenStreamComponents {
    val tokenizer = KeywordTokenizer()
    return TokenStreamComponents(tokenizer, FileNameTokenFilter(tokenizer))
  }

  override fun normalize(fieldName: String, inStream: TokenStream): TokenStream =
    LowerCaseFilter(inStream)
}


class FilePathAnalyzer : Analyzer() {
  override fun createComponents(fieldName: String): TokenStreamComponents {
    val tokenizer = KeywordTokenizer()
    return TokenStreamComponents(tokenizer, PathSegmentTokenFilter(tokenizer))
  }
}

private class PathSegmentTokenFilter(input: TokenStream) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val typeAttr = addAttribute(TypeAttribute::class.java)
  private val offsetAttr = addAttribute(OffsetAttribute::class.java)
  private val posIncrAttr = addAttribute(PositionIncrementAttribute::class.java)

  private val pending = ArrayDeque<PartInfo>()
  private var lastStartOffset = -1

  override fun incrementToken(): Boolean {
    if (pending.isNotEmpty()) {
      emitPending(pending.removeFirst())
      return true
    }

    if (!input.incrementToken()) return false

    val fullPath = termAttr.toString()
    lastStartOffset = -1

    // Full path as PATH token first (co-positional with first segment)
    pending.add(PartInfo(fullPath, TOKEN_TYPE_PATH, 0, fullPath.length))

    // Individual segments as PATH_SEGMENT tokens

    //TODO are we guaranteed to have / as delimiter?
    val parts = fullPath.split('/')
    var offset = 0
    for (part in parts) {
      if (part.isNotEmpty()) {
        pending.add(PartInfo(part, TOKEN_TYPE_PATH_SEGMENT, offset, offset + part.length))
      }
      offset += part.length + 1
    }

    emitPending(pending.removeFirst())
    return true
  }

  private fun emitPending(part: PartInfo) {
    termAttr.setEmpty().append(part.term)
    typeAttr.setType(part.type)
    offsetAttr.setOffset(part.start, part.end)
    posIncrAttr.positionIncrement = if (lastStartOffset == -1 || part.start != lastStartOffset) 1 else 0
    lastStartOffset = part.start
  }

  override fun reset() {
    super.reset()
    pending.clear()
    lastStartOffset = -1
  }
}


class FileTypeAnalyzer : Analyzer() {
  override fun createComponents(fieldName: String): TokenStreamComponents {
    val tokenizer = KeywordTokenizer()
    return TokenStreamComponents(tokenizer, TypeSettingTokenFilter(LowerCaseFilter(tokenizer), TOKEN_TYPE_FILETYPE))
  }
}

private class TypeSettingTokenFilter(input: TokenStream, private val type: String) : TokenFilter(input) {
  private val typeAttr = addAttribute(TypeAttribute::class.java)

  override fun incrementToken(): Boolean {
    if (!input.incrementToken()) return false
    typeAttr.setType(type)
    return true
  }
}
