package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.splitting.CamelCaseSplittingRule
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.splitting.LetterAndDigitSplittingRule
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.splitting.NumericTransitionSplittingRule
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.splitting.Span
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute

/**
 * Splits input tokens whose [MultiTypeAttribute] intersects [inputTypes] into sub-tokens.
 * Each sub-token is emitted with [outputType]; its offsets are absolute (input token start and span offset).
 *
 * [passThrough] controls if and how the original tokens are also emitted with their original type unchanged.
 *
 * Tokens whose type does not intersect [inputTypes] are forwarded unchanged.
 */
class WordSplittingTokenFilter(
  input: TokenStream,
  private val inputTypes: Set<FileTokenType>,
  private val outputType: FileTokenType,
  private val passThrough: PassthroughOptions = PassthroughOptions.PassthroughFirst,
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

  private val pending = ArrayDeque<BufferedToken>()

  override fun incrementToken(): Boolean {
    if (pending.isNotEmpty()) {
      emit(pending.removeFirst())
      return true
    }

    if (!input.incrementToken()) return false

    val activeTypes = multiTypeAttr.activeTypes().toSet()
    if (activeTypes.intersect(inputTypes).isEmpty()) {
      // No matching type — forward unchanged
      return true
    }

    val sourceTerm = termAttr.toString()
    val sourceStart = offsetAttr.startOffset()
    val sourceEnd = offsetAttr.endOffset()

    val subTokens = split(sourceTerm).map { span ->
      BufferedToken(
        sourceTerm.substring(span.first, span.last + 1),
        setOf(outputType),
        sourceStart + span.first,
        sourceStart + span.last + 1,
      )
    }

    val passThroughToken = BufferedToken(sourceTerm, activeTypes, sourceStart, sourceEnd)

    if (passThrough == PassthroughOptions.PassthroughFirst) pending.addLast(passThroughToken)
    pending.addAll(subTokens)
    if (passThrough == PassthroughOptions.PassthroughLast) pending.addLast(passThroughToken)

    emit(pending.removeFirst())
    return true
  }

  private fun emit(token: BufferedToken) {
    termAttr.setEmpty().append(token.term)
    multiTypeAttr.clearTypes().setTypes(token.types)
    offsetAttr.setOffset(token.startOffset, token.endOffset)
  }

  override fun reset() {
    super.reset()
    pending.clear()
  }

}

fun split(text: String): List<Span> {
  if (text.isEmpty()) return emptyList()
  val numericRule = NumericTransitionSplittingRule(text)
  val camelCaseRule = CamelCaseSplittingRule(text)
  val symbolRule = LetterAndDigitSplittingRule(text)
  return sequenceOf(text.indices)
    // Split by symbols: keep only spans of alphanumeric characters
    .flatMap { symbolRule.split(it) }
    // Split on transitions between letter and digit (passthrough + sub-spans)
    .addAllFlatMapped { numericRule.split(it) }
    // Generate splits for different cases.
    // We treat continuous uppercase sequences of length > 2 as a word;
    // at acronym boundaries with ≤ 2 preceding uppercase chars, each letter is its own span.
    .flatMap { camelCaseRule.split(it) }
    .distinct()
    .toList()
}


/** Emits each original element AND all elements produced by [block] for it. */
fun <T> Sequence<T>.addAllFlatMapped(block: (T) -> Sequence<T>): Sequence<T> {
  return this.flatMap { element ->
    sequenceOf(element) + block(element)
  }
}
