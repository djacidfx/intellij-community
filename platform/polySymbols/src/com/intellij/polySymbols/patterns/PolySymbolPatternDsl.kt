// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.patterns

import com.intellij.model.Pointer
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.PolySymbolApiStatus
import com.intellij.polySymbols.PolySymbolKind
import com.intellij.polySymbols.PolySymbolModifier
import com.intellij.polySymbols.PolySymbolProperty
import com.intellij.polySymbols.PolySymbolQualifiedName
import com.intellij.polySymbols.dsl.DependencyScope
import com.intellij.polySymbols.dsl.DependencySource
import com.intellij.polySymbols.dsl.PolySymbolDsl
import com.intellij.polySymbols.dsl.PolySymbolDslBuilderBase
import com.intellij.polySymbols.query.PolySymbolNameConversionRules
import com.intellij.polySymbols.query.PolySymbolScope
import com.intellij.polySymbols.webTypes.filters.PolySymbolFilter
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon

/**
 * Builds a [PolySymbolPattern].
 *
 * The body must produce at least one pattern. Multiple top-level items are
 * wrapped in an implicit sequence; use [PolySymbolPatternBuilder.sequence] to
 * be explicit.
 */
fun polySymbolPattern(body: PolySymbolPatternBuilder.() -> Unit): PolySymbolPattern =
  PolySymbolPatternBuilder().apply(body).buildSingle()

@PolySymbolDsl
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

  /**
   * Completion auto-popup trigger. The already input name prefix is discarded at this position.
   */
  fun completionPopup() {
    patterns += PolySymbolPatternFactory.createCompletionAutoPopup(false)
  }

  /**
   * Completion auto-popup trigger, which keeps the already input name prefix
   * on each completion item.
   */
  fun completionPopupWithPrefixKept() {
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
    patterns += GroupPatternBuilder(required = true).apply(body).buildGroup()
  }

  /** Optional group. */
  fun optional(body: GroupPatternBuilder.() -> Unit) {
    patterns += GroupPatternBuilder(required = false).apply(body).buildGroup()
  }

  /** Repeating group. */
  fun repeating(body: RepeatingGroupPatternBuilder.() -> Unit) {
    patterns += RepeatingGroupPatternBuilder(required = true).apply(body).buildGroup()
  }

  /** Optional repeating group. */
  fun optionalRepeating(body: RepeatingGroupPatternBuilder.() -> Unit) {
    patterns += RepeatingGroupPatternBuilder(required = false).apply(body).buildGroup()
  }

  internal fun buildSingle(): PolySymbolPattern {
    check(patterns.isNotEmpty()) { "Pattern body must produce at least one pattern" }
    return if (patterns.size == 1) patterns[0]
    else PolySymbolPatternFactory.createPatternSequence(*patterns.toTypedArray())
  }
}

@PolySymbolDsl
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

@PolySymbolDsl
open class GroupPatternBuilder internal constructor(
  private val required: Boolean,
) : PolySymbolPatternBuilder() {
  private var priorityValue: PolySymbol.Priority? = null
  private var apiStatusValue: PolySymbolApiStatus? = null
  private var symbolsResolverValue: PolySymbolPatternSymbolsResolver? = null
  private var matchPropertyOverrides: MatchPropertyOverridesBuilder? = null
  private val additionalScopes: MutableList<PolySymbolScope> = mutableListOf()
  protected open val repeats: Boolean get() = false
  protected open val unique: Boolean get() = false
  private var symbolsBuilder: SymbolsBuilder? = null
  private val alternatives: MutableList<PolySymbolPattern> = mutableListOf()

  fun priority(value: PolySymbol.Priority?) {
    priorityValue = value
  }

  fun apiStatus(value: PolySymbolApiStatus?) {
    apiStatusValue = value
  }

  /**
   * Direct access to a custom [PolySymbolPatternSymbolsResolver]. Mutually
   * exclusive with the [symbols] block; set this when you have a hand-rolled
   * resolver that does not map to [PolySymbolPatternReferenceResolver].
   */
  @ApiStatus.Internal
  fun symbolsResolver(value: PolySymbolPatternSymbolsResolver?) {
    symbolsResolverValue = value
  }

  /**
   * Specify property overrides for the resulting [com.intellij.polySymbols.query.PolySymbolMatch].
   *
   * When a pattern evaluates to a match, the resulting symbol's properties are
   * aggregated from the matched symbols, iterated right-to-left (so later
   * segments take precedence over earlier ones). This block installs a
   * synthetic zero-range segment at the end of the match, allowing you to
   * override core properties (`priority`, `apiStatus`, `modifiers`, `icon`)
   * and any custom [PolySymbolProperty].
   */
  fun overrideMatchProperties(body: MatchPropertyOverridesBuilder.() -> Unit) {
    val builder = matchPropertyOverrides ?: MatchPropertyOverridesBuilder().also { matchPropertyOverrides = it }
    builder.body()
  }

  /** Append additional scopes made available while matching this group's children. */
  fun additionalScope(vararg scopes: PolySymbolScope) {
    additionalScopes += scopes
  }

  /** Append additional scopes made available while matching this group's children. */
  fun additionalScope(scopes: Collection<PolySymbolScope>) {
    additionalScopes += scopes
  }

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
    check(symbolsBuilder == null || symbolsResolverValue == null) {
      "Group has both a symbols { } block and a symbolsResolver — pick one"
    }

    val resolver = symbolsResolverValue ?: symbolsBuilder?.buildResolver()

    val content: MutableList<PolySymbolPattern> = mutableListOf()
    content += alternatives
    if (patterns.isNotEmpty()) {
      content += if (patterns.size == 1) patterns[0]
      else PolySymbolPatternFactory.createPatternSequence(*patterns.toTypedArray())
    }
    check(content.isNotEmpty()) { "Group body must produce at least one pattern" }

    val options = ComplexPatternOptions(
      additionalScope = additionalScopes.toList(),
      apiStatus = apiStatusValue,
      isRequired = required,
      priority = priorityValue,
      repeats = repeats,
      unique = repeats && unique,
      symbolsResolver = resolver,
      additionalLastSegmentSymbol = matchPropertyOverrides?.build(),
    )
    return PolySymbolPatternFactory.createComplexPattern(options, false, *content.toTypedArray())
  }
}

@PolySymbolDsl
class RepeatingGroupPatternBuilder internal constructor(required: Boolean) : GroupPatternBuilder(required) {

  private var uniqueValue: Boolean = false

  fun unique(value: Boolean) {
    uniqueValue = value
  }

  override val unique: Boolean get() = uniqueValue
  override val repeats: Boolean get() = true
}

@PolySymbolDsl
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
      filter = builder.filterValue,
      excludeModifiers = builder.excludeModifiersValue,
      nameConversionRules = builder.nameConversionRules,
    )
  }

  internal fun buildResolver(): PolySymbolPatternReferenceResolver? {
    if (references.isEmpty()) return null
    return PolySymbolPatternReferenceResolver(*references.toTypedArray())
  }
}

@PolySymbolDsl
class ReferenceBuilder internal constructor() {

  internal var filterValue: PolySymbolFilter? = null
  internal var excludeModifiersValue: MutableList<PolySymbolModifier> = mutableListOf()

  internal val nameConversionRules: MutableList<PolySymbolNameConversionRules> = mutableListOf()

  fun filter(value: PolySymbolFilter?) {
    filterValue = value
  }

  fun excludeModifiers(vararg value: PolySymbolModifier) {
    excludeModifiersValue.addAll(value)
  }

  fun excludeModifiers(value: List<PolySymbolModifier>) {
    excludeModifiersValue.addAll(value)
  }

  fun nameConversion(rules: PolySymbolNameConversionRules) {
    nameConversionRules += rules
  }

  fun nameConversion(rules: Collection<PolySymbolNameConversionRules>) {
    nameConversionRules += rules
  }
}

@PolySymbolDsl
class MatchPropertyOverridesBuilder internal constructor() : PolySymbolDslBuilderBase() {
  override val builderContext: String get() = "overrideMatchProperties"

  /**
   * Resolve all declared dependencies and build the override symbol. Returns
   * `null` if no overrides were set, or if any declared dependency failed to
   * dereference (in which case the whole override contribution is dropped).
   */
  internal fun build(): PolySymbol? {
    if (priorityGetter == null
        && priorityValue == null
        && apiStatusGetter == null
        && apiStatusValue == null
        && modifiersGetter == null
        && modifiersValue == null
        && iconGetter == null
        && iconValue == null
        && propertyValues.isEmpty()
        && propertyGetters.isEmpty()) {
      return null
    }
    val resolved = resolveSnapshot() ?: return null
    return MatchPropertyOverrideSymbol(
      source = DependencySource.FromSpecs(depSpecs.toList()),
      scope = DependencyScope(resolved),
      priorityGetter = priorityGetter,
      priorityValue = priorityValue,
      apiStatusGetter = apiStatusGetter,
      apiStatusValue = apiStatusValue,
      modifiersGetter = modifiersGetter,
      modifiersValue = modifiersValue,
      iconGetter = iconGetter,
      iconValue = iconValue,
      propertyGetters = propertyGetters.toMap(),
      propertyValues = propertyValues.toMap(),
    )
  }
}

private val MATCH_PROPERTY_OVERRIDE_KIND: PolySymbolKind =
  PolySymbolKind["", "\$matchPropertyOverride$"]

private class MatchPropertyOverrideSymbol(
  private val source: DependencySource,
  private val scope: DependencyScope,
  private val priorityValue: PolySymbol.Priority?,
  private val priorityGetter: (() -> PolySymbol.Priority?)?,
  private val apiStatusValue: PolySymbolApiStatus?,
  private val apiStatusGetter: (() -> PolySymbolApiStatus)?,
  private val modifiersValue: Set<PolySymbolModifier>?,
  private val modifiersGetter: (() -> Set<PolySymbolModifier>)?,
  private val iconValue: Icon?,
  private val iconGetter: (() -> Icon?)?,
  private val propertyValues: Map<PolySymbolProperty<*>, Any?>,
  private val propertyGetters: Map<PolySymbolProperty<*>, () -> Any?>,
) : PolySymbol {
  override val kind: PolySymbolKind get() = MATCH_PROPERTY_OVERRIDE_KIND
  override val name: String get() = ""

  override val priority: PolySymbol.Priority?
    get() = priorityGetter?.let { scope.withinScope { it() } }
            ?: priorityValue
            ?: super.priority

  override val apiStatus: PolySymbolApiStatus
    get() = apiStatusGetter?.let { scope.withinScope { it() } }
            ?: apiStatusValue
            ?: super.apiStatus

  override val modifiers: Set<PolySymbolModifier>
    get() = modifiersGetter?.let { scope.withinScope { it() } }
            ?: modifiersValue
            ?: super.modifiers

  override val icon: Icon?
    get() = iconGetter?.let { scope.withinScope { it() } }
            ?: iconValue
            ?: super.icon

  override fun <T : Any> get(property: PolySymbolProperty<T>): T? {
    val getter = propertyGetters[property]
    if (getter != null) {
      @Suppress("UNCHECKED_CAST")
      return property.tryCast(scope.withinScope { (getter as () -> T?).invoke() })
    }
    if (propertyValues.containsKey(property)) {
      return property.tryCast(propertyValues[property])
    }
    return super.get(property)
  }

  override fun createPointer(): Pointer<out PolySymbol> {
    if (source.isEmpty) return Pointer.hardPointer(this)
    val pointerSource = DependencySource.FromPointers(source.pointers())
    val priorityValue = priorityValue
    val priorityGetter = priorityGetter
    val apiStatusValue = apiStatusValue
    val apiStatusGetter = apiStatusGetter
    val modifiersValue = modifiersValue
    val modifiersGetter = modifiersGetter
    val iconValue = iconValue
    val iconGetter = iconGetter
    val propertyValues = propertyValues
    val propertyGetters = propertyGetters
    return Pointer {
      val snapshot = pointerSource.snapshot() ?: return@Pointer null
      MatchPropertyOverrideSymbol(
        source = pointerSource,
        scope = DependencyScope(snapshot),
        priorityValue = priorityValue,
        priorityGetter = priorityGetter,
        apiStatusValue = apiStatusValue,
        apiStatusGetter = apiStatusGetter,
        modifiersValue = modifiersValue,
        modifiersGetter = modifiersGetter,
        iconValue = iconValue,
        iconGetter = iconGetter,
        propertyValues = propertyValues,
        propertyGetters = propertyGetters,
      )
    }
  }
}
