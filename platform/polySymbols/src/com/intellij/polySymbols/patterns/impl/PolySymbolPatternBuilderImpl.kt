// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.patterns.impl

import com.intellij.polySymbols.PolySymbolQualifiedName
import com.intellij.polySymbols.patterns.AlternativesBuilder
import com.intellij.polySymbols.patterns.ComplexPatternOptions
import com.intellij.polySymbols.patterns.GroupPatternBuilder
import com.intellij.polySymbols.patterns.PolySymbolPattern
import com.intellij.polySymbols.patterns.PolySymbolPatternBuilder
import com.intellij.polySymbols.patterns.PolySymbolPatternFactory
import com.intellij.polySymbols.patterns.RepeatingGroupPatternBuilder

internal open class PolySymbolPatternBuilderImpl : PolySymbolPatternBuilder {

  internal val patterns: MutableList<PolySymbolPattern> = mutableListOf()

  override fun literal(text: String) {
    patterns += PolySymbolPatternFactory.createStringMatch(text)
  }

  override fun regex(pattern: String, caseSensitive: Boolean) {
    patterns += PolySymbolPatternFactory.createRegExMatch(pattern, caseSensitive)
  }

  override fun symbolReference(label: String?) {
    patterns += PolySymbolPatternFactory.createSymbolReferencePlaceholder(label)
  }

  override fun completionPopup() {
    patterns += PolySymbolPatternFactory.createCompletionAutoPopup(false)
  }

  override fun completionPopupWithPrefixKept() {
    patterns += PolySymbolPatternFactory.createCompletionAutoPopup(true)
  }

  override fun symbolReference(vararg path: PolySymbolQualifiedName) {
    patterns += PolySymbolPatternFactory.createSingleSymbolReferencePattern(path.toList())
  }

  override fun symbolReference(path: List<PolySymbolQualifiedName>) {
    patterns += PolySymbolPatternFactory.createSingleSymbolReferencePattern(path)
  }

  override fun sequence(body: PolySymbolPatternBuilder.() -> Unit) {
    patterns += PolySymbolPatternBuilderImpl().apply(body).buildSingle()
  }

  override fun oneOf(body: AlternativesBuilder.() -> Unit) {
    val branches = AlternativesBuilderImpl().apply(body).buildBranches()
    patterns += PolySymbolPatternFactory.createComplexPattern(
      ComplexPatternOptions(), false, *branches.toTypedArray()
    )
  }

  override fun group(body: GroupPatternBuilder.() -> Unit) {
    patterns += GroupPatternBuilderImpl(required = true).apply(body).buildGroup()
  }

  override fun optional(body: GroupPatternBuilder.() -> Unit) {
    patterns += GroupPatternBuilderImpl(required = false).apply(body).buildGroup()
  }

  override fun repeating(body: RepeatingGroupPatternBuilder.() -> Unit) {
    patterns += RepeatingGroupPatternBuilderImpl(required = true).apply(body).buildGroup()
  }

  override fun optionalRepeating(body: RepeatingGroupPatternBuilder.() -> Unit) {
    patterns += RepeatingGroupPatternBuilderImpl(required = false).apply(body).buildGroup()
  }

  internal fun buildSingle(): PolySymbolPattern {
    check(patterns.isNotEmpty()) { "Pattern body must produce at least one pattern" }
    return if (patterns.size == 1) patterns[0]
    else PolySymbolPatternFactory.createPatternSequence(*patterns.toTypedArray())
  }
}
