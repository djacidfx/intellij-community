// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.patterns

import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.PolySymbolApiStatus
import com.intellij.polySymbols.PolySymbolKind
import com.intellij.polySymbols.PolySymbolModifier
import com.intellij.polySymbols.PolySymbolQualifiedName
import com.intellij.polySymbols.query.PolySymbolNameConversionRules
import com.intellij.polySymbols.webTypes.filters.PolySymbolFilter
import org.jetbrains.annotations.ApiStatus

@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class PolySymbolPatternDsl

/**
 * Builds a [PolySymbolPattern].
 *
 * This is the preferred programmatic API for composing patterns. The DSL
 * delegates to [PolySymbolPatternFactory] — the runtime graph is identical to
 * patterns built directly against the factory.
 *
 * The body must produce at least one pattern. Multiple top-level items are
 * wrapped in an implicit sequence; use [PolySymbolPatternBuilder.sequence] to
 * be explicit.
 */
@ApiStatus.Experimental
fun polySymbolPattern(body: PolySymbolPatternBuilder.() -> Unit): PolySymbolPattern =
  PolySymbolPatternBuilder().apply(body).buildSingle()

@PolySymbolPatternDsl
@ApiStatus.Experimental
open class PolySymbolPatternBuilder internal constructor() {

  internal val patterns: MutableList<PolySymbolPattern> = mutableListOf()

  /** Literal string match. */
  fun literal(text: String) {
    patterns += PolySymbolPatternFactory.createStringMatch(text)
  }

  /** Regex match. */
  fun regex(pattern: String, caseSensitive: Boolean = false) {
    patterns += PolySymbolPatternFactory.createRegExMatch(pattern, caseSensitive)
  }

  /**
   * Symbol reference placeholder. Resolves against the enclosing
   * `symbols { }` or custom `symbolsResolver`.
   */
  fun symbolReference(label: String? = null) {
    patterns += PolySymbolPatternFactory.createSymbolReferencePlaceholder(label)
  }

  /** Non-sticky completion auto-popup trigger. */
  fun completionPopup() {
    patterns += PolySymbolPatternFactory.createCompletionAutoPopup(false)
  }

  /** Sticky completion auto-popup trigger. */
  fun stickyCompletionPopup() {
    patterns += PolySymbolPatternFactory.createCompletionAutoPopup(true)
  }

  /** Reference to a specific symbol resolved along [path]. */
  fun symbolReference(vararg path: PolySymbolQualifiedName) {
    patterns += PolySymbolPatternFactory.createSingleSymbolReferencePattern(path.toList())
  }

  /** Reference to a specific symbol resolved along [path]. */
  fun symbolReference(path: List<PolySymbolQualifiedName>) {
    patterns += PolySymbolPatternFactory.createSingleSymbolReferencePattern(path)
  }

  /** Ordered sequence; all children must match in order. */
  fun sequence(body: PolySymbolPatternBuilder.() -> Unit) {
    patterns += PolySymbolPatternBuilder().apply(body).buildSingle()
  }

  /** Alternatives; match exactly one of the `branch { }` blocks. */
  open fun oneOf(body: AlternativesBuilder.() -> Unit) {
    val branches = AlternativesBuilder().apply(body).buildBranches()
    patterns += PolySymbolPatternFactory.createComplexPattern(
      ComplexPatternOptions(), false, *branches.toTypedArray()
    )
  }

  /** Pattern group with options and/or a symbol resolver. */
  fun group(body: GroupPatternBuilder.() -> Unit) {
    patterns += GroupPatternBuilder().apply(body).buildGroup()
  }

  /** Optional group. */
  fun optional(body: GroupPatternBuilder.() -> Unit) {
    patterns += GroupPatternBuilder().apply { required = false }.apply(body).buildGroup()
  }

  /** Repeating group. */
  fun repeating(body: RepeatingGroupPatternBuilder.() -> Unit) {
    patterns += RepeatingGroupPatternBuilder().apply(body).buildGroup()
  }

  /** Optional repeating group. */
  fun optionalRepeating(body: RepeatingGroupPatternBuilder.() -> Unit) {
    patterns += RepeatingGroupPatternBuilder().apply { required = false }.apply(body).buildGroup()
  }

  internal fun buildSingle(): PolySymbolPattern {
    check(patterns.isNotEmpty()) { "Pattern body must produce at least one pattern" }
    return if (patterns.size == 1) patterns[0]
    else PolySymbolPatternFactory.createPatternSequence(*patterns.toTypedArray())
  }
}

@PolySymbolPatternDsl
@ApiStatus.Experimental
class AlternativesBuilder internal constructor() {

  private val branches: MutableList<PolySymbolPattern> = mutableListOf()

  fun branch(body: PolySymbolPatternBuilder.() -> Unit) {
    branches += PolySymbolPatternBuilder().apply(body).buildSingle()
  }

  internal fun buildBranches(): List<PolySymbolPattern> {
    check(branches.isNotEmpty()) { "oneOf must contain at least one branch" }
    return branches.toList()
  }
}

@PolySymbolPatternDsl
@ApiStatus.Experimental
open class GroupPatternBuilder internal constructor() : PolySymbolPatternBuilder() {
  var priority: PolySymbol.Priority? = null
  var apiStatus: PolySymbolApiStatus? = null
  var scope: PolySymbol? = null
  var additionalLastSegmentSymbol: PolySymbol? = null

  internal var required: Boolean = true
  internal open val repeats: Boolean get() = false
  internal open val unique: Boolean get() = false

  /**
   * Direct access to a custom [PolySymbolPatternSymbolsResolver]. Mutually
   * exclusive with the [symbols] block; set this when you have a hand-rolled
   * resolver that does not map to [PolySymbolPatternReferenceResolver].
   */
  var symbolsResolver: PolySymbolPatternSymbolsResolver? = null

  private var symbolsBuilder: SymbolsBuilder? = null
  private val alternatives: MutableList<PolySymbolPattern> = mutableListOf()

  /**
   * Hoists the alternatives into the enclosing group.
   */
  override fun oneOf(body: AlternativesBuilder.() -> Unit) {
    alternatives += AlternativesBuilder().apply(body).buildBranches()
  }

  /** Symbol resolver built from one or more `from(kind, ...)` entries. */
  fun symbols(body: SymbolsBuilder.() -> Unit) {
    val builder = symbolsBuilder ?: SymbolsBuilder().also { symbolsBuilder = it }
    builder.body()
  }

  internal fun buildGroup(): PolySymbolPattern {
    check(symbolsBuilder == null || symbolsResolver == null) {
      "Group has both a symbols { } block and a symbolsResolver — pick one"
    }

    val resolver = symbolsResolver ?: symbolsBuilder?.buildResolver()

    val content: MutableList<PolySymbolPattern> = mutableListOf()
    content += alternatives
    if (patterns.isNotEmpty()) {
      content += if (patterns.size == 1) patterns[0]
      else PolySymbolPatternFactory.createPatternSequence(*patterns.toTypedArray())
    }
    check(content.isNotEmpty()) { "Group body must produce at least one pattern" }

    val options = ComplexPatternOptions(
      additionalScope = scope,
      apiStatus = apiStatus,
      isRequired = required,
      priority = priority,
      repeats = repeats,
      unique = repeats && unique,
      symbolsResolver = resolver,
      additionalLastSegmentSymbol = additionalLastSegmentSymbol,
    )
    return PolySymbolPatternFactory.createComplexPattern(options, false, *content.toTypedArray())
  }
}

@PolySymbolPatternDsl
@ApiStatus.Experimental
class RepeatingGroupPatternBuilder internal constructor() : GroupPatternBuilder() {

  public override var unique: Boolean = false

  override val repeats: Boolean get() = true
}

@PolySymbolPatternDsl
@ApiStatus.Experimental
class SymbolsBuilder internal constructor() {

  private val references: MutableList<PolySymbolPatternReferenceResolver.Reference> = mutableListOf()

  /** Add a reference to symbols of the given [kind], optionally scoped under [location]. */
  fun from(
    kind: PolySymbolKind,
    location: List<PolySymbolQualifiedName> = emptyList(),
    body: ReferenceBuilder.() -> Unit = {},
  ) {
    val builder = ReferenceBuilder().apply(body)
    references += PolySymbolPatternReferenceResolver.Reference(
      location = location,
      kind = kind,
      filter = builder.filter,
      excludeModifiers = builder.excludeModifiers,
      nameConversionRules = builder.nameConversionRules,
    )
  }

  internal fun buildResolver(): PolySymbolPatternReferenceResolver? {
    if (references.isEmpty()) return null
    return PolySymbolPatternReferenceResolver(*references.toTypedArray())
  }
}

@PolySymbolPatternDsl
@ApiStatus.Experimental
class ReferenceBuilder internal constructor() {

  var filter: PolySymbolFilter? = null
  var excludeModifiers: List<PolySymbolModifier> = listOf(PolySymbolModifier.ABSTRACT)

  internal val nameConversionRules: MutableList<PolySymbolNameConversionRules> = mutableListOf()

  fun nameConversion(rules: PolySymbolNameConversionRules) {
    nameConversionRules += rules
  }

  fun nameConversion(rules: Collection<PolySymbolNameConversionRules>) {
    nameConversionRules += rules
  }
}
