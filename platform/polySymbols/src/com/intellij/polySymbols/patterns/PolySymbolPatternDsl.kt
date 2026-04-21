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
import com.intellij.polySymbols.patterns.impl.PolySymbolPatternBuilderImpl
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
  PolySymbolPatternBuilderImpl().apply(body).buildSingle()

@PolySymbolDsl
interface PolySymbolPatternBuilder {

  /** Literal string match. */
  fun literal(text: String)

  /** Regex match. */
  fun regex(pattern: String, caseSensitive: Boolean = false)

  /**
   * Symbol reference placeholder. Resolves against the enclosing
   * `symbols { }` or custom `symbolsResolver`.
   */
  fun symbolReference(label: String? = null)

  /**
   * Completion auto-popup trigger. The already input name prefix is discarded at this position.
   */
  fun completionPopup()

  /**
   * Completion auto-popup trigger, which keeps the already input name prefix
   * on each completion item.
   */
  fun completionPopupWithPrefixKept()

  /** Reference to a specific symbol resolved along [path]. */
  fun symbolReference(vararg path: PolySymbolQualifiedName)

  /** Reference to a specific symbol resolved along [path]. */
  fun symbolReference(path: List<PolySymbolQualifiedName>)

  /** Ordered sequence; all children must match in order. */
  fun sequence(body: PolySymbolPatternBuilder.() -> Unit)

  /** Alternatives; match exactly one of the `branch { }` blocks. */
  fun oneOf(body: AlternativesBuilder.() -> Unit)

  /** Pattern group with options and/or a symbol resolver. */
  fun group(body: GroupPatternBuilder.() -> Unit)

  /** Optional group. */
  fun optional(body: GroupPatternBuilder.() -> Unit)

  /** Repeating group. */
  fun repeating(body: RepeatingGroupPatternBuilder.() -> Unit)

  /** Optional repeating group. */
  fun optionalRepeating(body: RepeatingGroupPatternBuilder.() -> Unit)
}

@PolySymbolDsl
interface AlternativesBuilder {
  fun branch(body: PolySymbolPatternBuilder.() -> Unit)
}

@PolySymbolDsl
interface GroupPatternBuilder : PolySymbolPatternBuilder {

  fun priority(value: PolySymbol.Priority?)

  fun apiStatus(value: PolySymbolApiStatus?)

  /**
   * Direct access to a custom [PolySymbolPatternSymbolsResolver]. Mutually
   * exclusive with the [symbols] block; set this when you have a hand-rolled
   * resolver that does not map to [PolySymbolPatternReferenceResolver].
   */
  @ApiStatus.Internal
  fun symbolsResolver(value: PolySymbolPatternSymbolsResolver?)

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
  fun overrideMatchProperties(body: MatchPropertyOverridesBuilder.() -> Unit)

  /** Append additional scopes made available while matching this group's children. */
  fun additionalScope(vararg scopes: PolySymbolScope)

  /** Append additional scopes made available while matching this group's children. */
  fun additionalScope(scopes: Collection<PolySymbolScope>)

  /** Symbol resolver built from one or more `from(kind, ...)` entries. */
  fun symbols(body: SymbolsBuilder.() -> Unit)
}

@PolySymbolDsl
interface RepeatingGroupPatternBuilder : GroupPatternBuilder {
  fun unique(value: Boolean)
}

@PolySymbolDsl
interface SymbolsBuilder {

  /** Add a reference to symbols of the given [kind], optionally scoped under [location]. */
  fun from(
    kind: PolySymbolKind,
    location: List<PolySymbolQualifiedName> = emptyList(),
    body: ReferenceBuilder.() -> Unit = {},
  )
}

@PolySymbolDsl
interface ReferenceBuilder {

  fun filter(value: PolySymbolFilter?)

  fun excludeModifiers(vararg value: PolySymbolModifier)

  fun excludeModifiers(value: List<PolySymbolModifier>)

  fun nameConversion(rules: PolySymbolNameConversionRules)

  fun nameConversion(rules: Collection<PolySymbolNameConversionRules>)
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
    val resolved = resolveSnapshot()
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
  PolySymbolKind["", $$"$matchPropertyOverride$"]

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
