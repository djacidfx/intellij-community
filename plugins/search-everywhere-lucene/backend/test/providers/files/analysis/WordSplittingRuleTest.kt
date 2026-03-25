package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.splitting.LetterAndDigitSplittingRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WordSplittingRuleTest {

  // ---- SymbolSplittingRule ----

  @Test
  fun `LetterAndDigitSplittingRule splits on single delimiter`() {
    assertEquals(LetterAndDigitSplittingRule("").split().toList(), emptyList<IntRange>())
    assertEquals(LetterAndDigitSplittingRule("hello").split().toList(), listOf(0 until 5))
    assertEquals(LetterAndDigitSplittingRule("foo/bar/baz").split().toList(), listOf(0 until 3, 4 until 7, 8 until 11))
    assertEquals(LetterAndDigitSplittingRule("foo//bar").split().toList(), listOf(0 until 3, 5 until 8))
  }

}
