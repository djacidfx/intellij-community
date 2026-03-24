package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.KeywordTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute


internal data class PartInfo(val term: String, val type: FileTokenType, val start: Int, val end: Int)

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
      result.add(PartInfo(text.substring(start, i), FileTokenType.FILENAME_PART, start, i))
      start = i
    }
  }
  result.add(PartInfo(text.substring(start), FileTokenType.FILENAME_PART, start, text.length))
  return result
}

/**
 * Builds all filename sub-tokens for a given name stem, offsetting by [baseOffset].
 * Emits: FILENAME token, FILENAME_ABBREVIATION (if initials.length > 1), and FILENAME_PART tokens.
 * All returned offsets are absolute (baseOffset-relative).
 */
internal fun buildFilenameSubTokens(name: String, baseOffset: Int): List<PartInfo> {
  val result = mutableListOf<PartInfo>()
  result.add(PartInfo(name.lowercase(), FileTokenType.FILENAME, baseOffset, baseOffset + name.length))

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
    result.add(PartInfo(initials, FileTokenType.FILENAME_ABBREVIATION, baseOffset, baseOffset + name.length))
  }

  for (part in parts) {
    result.add(PartInfo(part.term.lowercase(), FileTokenType.FILENAME_PART, baseOffset + part.start, baseOffset + part.end))
  }
  return result
}


/**
 * Merges a flat list of [PartInfo] entries into a deduplicated list where entries sharing
 * the same (term, start, end) are collapsed into one [Pair] carrying all their types.
 * Insertion order (first occurrence) is preserved for correct Lucene offset ordering.
 */
internal fun buildMergedPending(parts: List<PartInfo>): List<Pair<PartInfo, Set<FileTokenType>>> {
  val map = LinkedHashMap<Triple<String, Int, Int>, Pair<PartInfo, MutableSet<FileTokenType>>>()
  for (part in parts) {
    val key = Triple(part.term, part.start, part.end)
    map.getOrPut(key) { Pair(part, mutableSetOf()) }.second.add(part.type)
  }
  return map.values.map { (part, types) -> Pair(part, types as Set<FileTokenType>) }
}


/**
 * Tokenizes a single filename (no path separators) into:
 *  - TOKEN_TYPE_PATH — full original text (case-preserved)
 *  - TOKEN_TYPE_FILENAME + TOKEN_TYPE_FILENAME_ABBREVIATION + TOKEN_TYPE_FILENAME_PART — name stem analysis
 *  - TOKEN_TYPE_FILETYPE — extension (lowercase)
 *
 * Tokens are emitted in non-decreasing offset order as required by Lucene.
 */
internal class FileNameTokenFilter(input: TokenStream) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val multiTypeAttr = addAttribute(MultiTypeAttribute::class.java)
  private val offsetAttr = addAttribute(OffsetAttribute::class.java)
  private val posIncrAttr = addAttribute(PositionIncrementAttribute::class.java)

  private val pending = ArrayDeque<Pair<PartInfo, Set<FileTokenType>>>()
  private var lastStartOffset = -1

  override fun incrementToken(): Boolean {
    if (pending.isNotEmpty()) {
      val (part, types) = pending.removeFirst()
      emitPending(part, types)
      return true
    }

    if (!input.incrementToken()) return false

    val fullText = termAttr.toString()
    lastStartOffset = -1

    val parts = mutableListOf<PartInfo>()

    // Full text as PATH token (case-preserved)
    parts.add(PartInfo(fullText, FileTokenType.PATH, 0, fullText.length))

    val dotIndex = fullText.lastIndexOf('.')
    val (nameStem, ext, nameStart, extStart) = when {
      dotIndex < 0  -> Quadruple(fullText, null, 0, -1)
      dotIndex == 0 -> Quadruple(fullText, fullText.substring(1), 0, 1)
      else          -> Quadruple(fullText.substring(0, dotIndex), fullText.substring(dotIndex + 1), 0, dotIndex + 1)
    }

    // Filename sub-tokens first (offsets at/near 0 must precede FILETYPE to stay non-decreasing)
    parts.addAll(buildFilenameSubTokens(nameStem, nameStart))

    // Extension last (highest offset)
    if (!ext.isNullOrEmpty()) {
      parts.add(PartInfo(ext.lowercase(), FileTokenType.FILETYPE, extStart, extStart + ext.length))
    }

    for (merged in buildMergedPending(parts)) {
      pending.add(merged)
    }

    val (part, types) = pending.removeFirst()
    emitPending(part, types)
    return true
  }

  private fun emitPending(part: PartInfo, types: Set<FileTokenType>) {
    termAttr.setEmpty().append(part.term)
    multiTypeAttr.clearTypes().setTypes(types)
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
  private val multiTypeAttr = addAttribute(MultiTypeAttribute::class.java)
  private val offsetAttr = addAttribute(OffsetAttribute::class.java)
  private val posIncrAttr = addAttribute(PositionIncrementAttribute::class.java)

  private val pending = ArrayDeque<Pair<PartInfo, Set<FileTokenType>>>()
  private var lastStartOffset = -1

  override fun incrementToken(): Boolean {
    if (pending.isNotEmpty()) {
      val (part, types) = pending.removeFirst()
      emitPending(part, types)
      return true
    }

    if (!input.incrementToken()) return false

    val fullPath = termAttr.toString()
    lastStartOffset = -1

    val parts = mutableListOf<PartInfo>()

    // Full path as PATH token first (co-positional with first segment)
    parts.add(PartInfo(fullPath, FileTokenType.PATH, 0, fullPath.length))

    // Individual segments as PATH_SEGMENT tokens

    //TODO are we guaranteed to have / as delimiter?
    val pathParts = fullPath.split('/')
    var offset = 0
    for (pathPart in pathParts) {
      if (pathPart.isNotEmpty()) {
        parts.add(PartInfo(pathPart, FileTokenType.PATH_SEGMENT, offset, offset + pathPart.length))
      }
      offset += pathPart.length + 1
    }

    for (merged in buildMergedPending(parts)) {
      pending.add(merged)
    }

    val (part, types) = pending.removeFirst()
    emitPending(part, types)
    return true
  }

  private fun emitPending(part: PartInfo, types: Set<FileTokenType>) {
    termAttr.setEmpty().append(part.term)
    multiTypeAttr.clearTypes().setTypes(types)
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
    return TokenStreamComponents(tokenizer, TypeSettingTokenFilter(LowerCaseFilter(tokenizer), FileTokenType.FILETYPE))
  }
}

private class TypeSettingTokenFilter(input: TokenStream, private val type: FileTokenType) : TokenFilter(input) {
  private val multiTypeAttr = addAttribute(MultiTypeAttribute::class.java)

  override fun incrementToken(): Boolean {
    if (!input.incrementToken()) return false
    multiTypeAttr.clearTypes()
    multiTypeAttr.setTypes(setOf(type))

    return true
  }
}
