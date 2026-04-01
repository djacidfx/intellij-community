package com.intellij.searchEverywhereMl.typos

import com.intellij.ide.actions.searcheverywhere.SearchEverywhereSpellCheckResult
import com.intellij.searchEverywhereMl.typos.models.PhrasePrefixIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class SearchEverywhereSpellerImplTest {
  @Test
  fun `selectCorrections drops original query duplicates and low confidence`() {
    val corrections = listOf(
      SearchEverywhereSpellCheckResult.Correction("show color picker", 0.92),
      SearchEverywhereSpellCheckResult.Correction("show color picker", 0.88),
      SearchEverywhereSpellCheckResult.Correction("show colour picker", 0.79),
      SearchEverywhereSpellCheckResult.Correction("show colr piker", 0.95),
      SearchEverywhereSpellCheckResult.Correction("show column picker", 0.41),
    )

    val actual = selectCorrections(
      query = "show colr piker",
      corrections = corrections,
      maxCorrections = 5,
      minConfidence = 0.5,
    )

    assertEquals(
      listOf(
        SearchEverywhereSpellCheckResult.Correction("show color picker", 0.92),
        SearchEverywhereSpellCheckResult.Correction("show colour picker", 0.79),
      ),
      actual,
    )
  }

  @Test
  fun `selectCorrections applies max corrections after filtering`() {
    val corrections = listOf(
      SearchEverywhereSpellCheckResult.Correction("first", 0.95),
      SearchEverywhereSpellCheckResult.Correction("second", 0.91),
      SearchEverywhereSpellCheckResult.Correction("third", 0.89),
    )

    val actual = selectCorrections(
      query = "query",
      corrections = corrections,
      maxCorrections = 2,
      minConfidence = 0.5,
    )

    assertEquals(corrections.take(2), actual)
  }

  @Test
  fun `prefix index matches exact and partial phrase prefixes`() {
    val index = PhrasePrefixIndex.create(listOf("show color picker", "runtime", "go to action"))

    assertTrue(index.hasPrefix("runti"))
    assertTrue(index.hasPrefix("runtime"))
    assertTrue(index.hasPrefix("show col"))
    assertTrue(index.hasPrefix("color"))
    assertTrue(index.hasPrefix("go to"))
  }

  @Test
  fun `prefix index rejects typos and mid-word substrings`() {
    val index = PhrasePrefixIndex.create(listOf("show color picker", "runtime"))

    assertFalse(index.hasPrefix("rantime"))
    assertFalse(index.hasPrefix("olor"))
    assertFalse(index.hasPrefix("show colr"))
  }

  @Test
  fun `shouldSkipTypoCorrection normalizes separators and case`() {
    val index = PhrasePrefixIndex.create(listOf("show color picker", "runtime"))

    assertTrue(shouldSkipTypoCorrection("Show-Col", index))
    assertTrue(shouldSkipTypoCorrection("color", index))
    assertTrue(shouldSkipTypoCorrection("RUNTI", index))
    assertFalse(shouldSkipTypoCorrection("123", index))
    assertFalse(shouldSkipTypoCorrection("Show Colr", index))
  }
}
