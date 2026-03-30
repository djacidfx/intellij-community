package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.splitting.PathSplittingRule
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.WhitespaceTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.util.Attribute
import org.apache.lucene.util.AttributeImpl
import org.apache.lucene.util.AttributeReflector



class FileSearchAnalyzer : Analyzer() {
  override fun createComponents(fieldName: String): TokenStreamComponents {
    val tokenizer = WhitespaceTokenizer()
    var stream: TokenStream = WordIndexFilter(tokenizer)
    stream = SearchPathTypeFilter(stream)
    stream = WordSplittingTokenFilter(stream, setOf(FileTokenType.FILENAME), FileTokenType.FILENAME_PART, PassthroughOptions.PassthroughLast)
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
    return TokenStreamComponents(tokenizer, stream)
  }
}

/**
 * Converts each whitespace token into typed sub-tokens representing path components, filename, and extension.
 *
 * For each input token, emits (in order):
 *   - PATH: full token, original case
 *   - PATH_SEGMENT: each /-split component except the last (original case)
 *   - PATH_SEGMENT: last component (original case) — only for multi-component paths or extension-less single-component
 *   - FILENAME: last-component stem, original case (lowercasing deferred to AbbreviationTokenFilter)
 *   - FILETYPE: extension or hidden-file body, lowercase
 *
 * Hidden files (leading dot, e.g. ".gitignore"): whole token is FILENAME; body after dot is FILETYPE.
 * Extension-less single-component: additionally emits PATH_SEGMENT and FILETYPE (lowercased whole term).
 * Both '/' and '\' are treated as path separators.
 */
class SearchPathTypeFilter(input: TokenStream) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val multiTypeAttr = addAttribute(MultiTypeAttribute::class.java)
  private val offsetAttr = addAttribute(OffsetAttribute::class.java)

  private data class PendingToken(
    val term: String,
    val types: Set<FileTokenType>,
    val startOffset: Int,
    val endOffset: Int,
  )

  private val pending = ArrayDeque<PendingToken>()

  override fun incrementToken(): Boolean {
    if (pending.isNotEmpty()) {
      emit(pending.removeFirst())
      return true
    }

    if (!input.incrementToken()) return false

    val segment = termAttr.toString()
    val segmentStart = offsetAttr.startOffset()
    val segmentEnd = offsetAttr.endOffset()

    pending.add(PendingToken(segment, setOf(FileTokenType.PATH), segmentStart, segmentEnd))

    // Normalise '\' to '/' so PathSplittingRule (which only splits on '/') handles both separators.
    // Character positions are unchanged since both are single chars.
    val normalizedSegment = segment.replace('\\', '/')
    val pathSpans = PathSplittingRule(normalizedSegment).split().toList()

    for (i in 0 until pathSpans.size - 1) {
      val span = pathSpans[i]
      pending.add(PendingToken(
        segment.substring(span.first, span.last + 1),
        setOf(FileTokenType.PATH_SEGMENT),
        segmentStart + span.first,
        segmentStart + span.last + 1,
      ))
    }

    if (pathSpans.isEmpty()) {
      emit(pending.removeFirst())
      return true
    }

    val lastSpan = pathSpans.last()
    val lastPart = segment.substring(lastSpan.first, lastSpan.last + 1)
    val partStart = segmentStart + lastSpan.first
    val partEnd = segmentStart + lastSpan.last + 1

    if (pathSpans.size > 1) {
      pending.add(PendingToken(lastPart, setOf(FileTokenType.PATH_SEGMENT), partStart, partEnd))
    }

    val dotIndex = lastPart.lastIndexOf('.')
    when {
      dotIndex < 0 -> {
        // Extension-less file
        pending.add(PendingToken(lastPart, setOf(FileTokenType.FILENAME), partStart, partEnd))
        if (lastPart.isNotEmpty()) {
          pending.add(PendingToken(lastPart.lowercase(), setOf(FileTokenType.FILETYPE), partStart, partEnd))
        }
        if (pathSpans.size == 1 && lastPart.isNotEmpty()) {
          pending.add(PendingToken(lastPart, setOf(FileTokenType.PATH_SEGMENT), partStart, partEnd))
        }
      }
      dotIndex == 0 -> {
        // Hidden file, e.g. ".gitignore": whole token is FILENAME, body after dot is FILETYPE
        pending.add(PendingToken(lastPart, setOf(FileTokenType.FILENAME), partStart, partEnd))
        val filetype = lastPart.substring(1)
        if (filetype.isNotEmpty()) {
          pending.add(PendingToken(filetype.lowercase(), setOf(FileTokenType.FILETYPE), partStart + 1, partEnd))
        }
      }
      else -> {
        val filename = lastPart.substring(0, dotIndex)
        val filetype = lastPart.substring(dotIndex + 1)
        pending.add(PendingToken(filename, setOf(FileTokenType.FILENAME), partStart, partStart + dotIndex))
        if (filetype.isNotEmpty()) {
          pending.add(PendingToken(filetype.lowercase(), setOf(FileTokenType.FILETYPE), partStart + dotIndex + 1, partEnd))
        }
      }
    }

    emit(pending.removeFirst())
    return true
  }

  private fun emit(token: PendingToken) {
    termAttr.setEmpty().append(token.term)
    multiTypeAttr.clearTypes().setTypes(token.types)
    offsetAttr.setOffset(token.startOffset, token.endOffset)
  }

  override fun reset() {
    super.reset()
    pending.clear()
  }
}


/**
 * Generates non-overlapping bigrams (step 2) from single-word filenames.
 *
 * Counts [FileTokenType.FILENAME_PART] tokens as they pass through; resets the count on each
 * [FileTokenType.FILENAME] token. When a FILENAME token arrives with exactly one preceding part
 * and the term length is ≥ 4, bigrams are queued as {FILENAME_PART, PATH_SEGMENT_PREFIX} tokens
 * at the FILENAME's offset. The FILENAME token itself is emitted immediately; bigrams follow on
 * subsequent [incrementToken] calls.
 */
class FilenameNgramFilter(input: TokenStream) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val multiTypeAttr = addAttribute(MultiTypeAttribute::class.java)
  private val offsetAttr = addAttribute(OffsetAttribute::class.java)

  private data class PendingToken(
    val term: String,
    val types: Set<FileTokenType>,
    val startOffset: Int,
    val endOffset: Int,
  )

  private val pending = ArrayDeque<PendingToken>()
  private var partCount = 0

  override fun incrementToken(): Boolean {
    if (pending.isNotEmpty()) {
      emit(pending.removeFirst())
      return true
    }

    if (!input.incrementToken()) return false

    val activeTypes = multiTypeAttr.activeTypes().toSet()
    when {
      activeTypes.contains(FileTokenType.FILENAME) -> {
        if (partCount == 1) {
          val term = termAttr.toString()
          if (term.length >= 4) {
            val start = offsetAttr.startOffset()
            val end = offsetAttr.endOffset()
            val ngramTypes = setOf(FileTokenType.FILENAME_PART, FileTokenType.PATH_SEGMENT_PREFIX)
            var i = 0
            while (i + 2 <= term.length) {
              pending.add(PendingToken(term.substring(i, i + 2), ngramTypes, start, end))
              i += 2
            }
          }
        }
        partCount = 0
      }
      activeTypes.contains(FileTokenType.FILENAME_PART) -> partCount++
    }

    return true
  }

  private fun emit(token: PendingToken) {
    termAttr.setEmpty().append(token.term)
    multiTypeAttr.clearTypes().setTypes(token.types)
    offsetAttr.setOffset(token.startOffset, token.endOffset)
  }

  override fun reset() {
    super.reset()
    pending.clear()
    partCount = 0
  }
}


/**
 * Assigns [WordAttribute.wordIndex] by incrementing a counter each time
 * [PositionIncrementAttribute.positionIncrement] > 0 (set by the preceding
 * [PositionIncrementFromOffsetFilter]). The counter starts at -1, so the first token
 * (which always has posIncr = 1) gets wordIndex = 0.
 */
class WordIndexFilter(input: TokenStream) : TokenFilter(input) {
  private val posIncrAttr = addAttribute(PositionIncrementAttribute::class.java)
  private val wordAttr = addAttribute(WordAttribute::class.java)

  private var wordIndex = -1

  override fun incrementToken(): Boolean {
    if (!input.incrementToken()) return false
    if (posIncrAttr.positionIncrement > 0) wordIndex++
    wordAttr.wordIndex = wordIndex
    return true
  }

  override fun reset() {
    super.reset()
    wordIndex = -1
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
