package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute

/**
 * Derives abbreviation tokens from groups of [sourceTypes] sub-tokens (e.g., FILENAME_PART).
 *
 * Tokens of [sourceTypes] are buffered. When a non-source token arrives (or the stream ends),
 * the buffer is flushed: abbreviation tokens are emitted, then (depending on [passThrough]) the
 * buffered parts are re-emitted lowercased, followed by the flushing non-source token lowercased.
 *
 * [allowedSkip] controls how many parts may be omitted per abbreviation: all ordered subsequences
 * with ≤ [allowedSkip] omissions are emitted. Default 0 produces one abbreviation.
 *
 * Abbreviation of a token sequence: first char of each token (lowercased)
 */
class AbbreviationTokenFilter(
  input: TokenStream,
  private val sourceTypes: Set<FileTokenType>,
  private val outputType: FileTokenType,
  private val minLength: Int = 2,
  private val allowedSkip: Int = 0,
  private val skipOutputType: FileTokenType = FileTokenType.FILENAME_ABBREVIATION_WITH_SKIPS,
  private val passThrough: PassthroughOptions = PassthroughOptions.PassthroughLast,
) : TokenFilter(input) {

  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val multiTypeAttr = addAttribute(MultiTypeAttribute::class.java)
  private val offsetAttr = addAttribute(OffsetAttribute::class.java)

  private data class BufferedToken(
    val term: String,
    val types: Set<FileTokenType>,
    val startOffset: Int,
    val endOffset: Int,
  )

  private val bufferedParts = mutableListOf<BufferedToken>()
  private val pending = ArrayDeque<BufferedToken>()

  override fun incrementToken(): Boolean {
    while (true) {
      if (pending.isNotEmpty()) {
        emit(pending.removeFirst())
        return true
      }

      if (!input.incrementToken()) {
        if (bufferedParts.isNotEmpty()) {
          flushBuffered()
          continue
        }
        return false
      }

      val activeTypes = multiTypeAttr.activeTypes().toSet()

      if (activeTypes.intersect(sourceTypes).isNotEmpty()) {
        bufferedParts.add(BufferedToken(termAttr.toString(), activeTypes, offsetAttr.startOffset(), offsetAttr.endOffset()))
        continue
      }

      val incoming = BufferedToken(termAttr.toString(), activeTypes, offsetAttr.startOffset(), offsetAttr.endOffset())
      if (bufferedParts.isNotEmpty()) {
        flushBuffered()
        pending.add(incoming.copy(term = incoming.term.lowercase()))
        continue
      }

      // Forward unchanged
      return true
    }
  }

  private fun flushBuffered() {
    val abbreviations = buildAbbreviations(bufferedParts)
    when (passThrough) {
      PassthroughOptions.PassthroughLast -> {
        pending.addAll(abbreviations)
        bufferedParts.mapTo(pending) { it.copy(term = it.term.lowercase()) }
      }
      PassthroughOptions.PassthroughFirst -> {
        bufferedParts.mapTo(pending) { it.copy(term = it.term.lowercase()) }
        pending.addAll(abbreviations)
      }
      PassthroughOptions.NoPassthrough -> {
        pending.addAll(abbreviations)
      }
    }

    bufferedParts.clear()
  }

  private fun buildAbbreviations(parts: List<BufferedToken>): List<BufferedToken> {
    val abbrevStart = parts.minOf { it.startOffset }
    val abbrevEnd = parts.maxOf { it.endOffset }
    val result = mutableListOf<BufferedToken>()
    val seen = mutableSetOf<String>()
    val minSubsetSize = maxOf(1, parts.size - allowedSkip)
    for (size in parts.size downTo minSubsetSize) {
      val type = if (size < parts.size) skipOutputType else outputType
      forEachCombination(parts.size, size) { indices ->
        val subParts = indices.map { parts[it] }
        val abbrev = buildAbbreviation(subParts)
        if (abbrev.length >= minLength && seen.add(abbrev)) {
          result.add(BufferedToken(abbrev, setOf(type), abbrevStart, abbrevEnd))
        }
        // Progressive abbreviations: extend the last part with 2, 3, ... chars
        // so that e.g. "MHT" can match "MyHTTP" via 'm'+'ht'.
        val prefix = buildAbbreviation(subParts.dropLast(1))
        val lastTerm = subParts.last().term.lowercase()
        for (len in 2..lastTerm.length) {
          val progressive = prefix + lastTerm.substring(0, len)
          if (progressive.length >= minLength && seen.add(progressive)) {
            result.add(BufferedToken(progressive, setOf(type), abbrevStart, abbrevEnd))
          }
        }
      }
    }
    return result
  }

  /**
   * Builds the abbreviation string: first char of each part (lowercased). */
  private fun buildAbbreviation(parts: List<BufferedToken>): String {
    val sb = StringBuilder()
    for (part in parts) {
      if (part.term.isEmpty()) continue
      sb.append(part.term[0].lowercaseChar())
    }
    return sb.toString()
  }

  private fun forEachCombination(n: Int, k: Int, action: (IntArray) -> Unit) {
    if (k == 0 || k > n) return
    val indices = IntArray(k) { it }
    while (true) {
      action(indices)
      var i = k - 1
      while (i >= 0 && indices[i] == n - k + i) i--
      if (i < 0) break
      indices[i]++
      for (j in i + 1 until k) indices[j] = indices[j - 1] + 1
    }
  }

  private fun emit(token: BufferedToken) {
    termAttr.setEmpty().append(token.term)
    multiTypeAttr.clearTypes().setTypes(token.types)
    offsetAttr.setOffset(token.startOffset, token.endOffset)
  }

  override fun reset() {
    super.reset()
    bufferedParts.clear()
    pending.clear()
  }
}