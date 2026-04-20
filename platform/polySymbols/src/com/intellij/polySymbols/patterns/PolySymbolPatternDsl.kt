// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.patterns

import com.intellij.model.Pointer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.PolySymbolApiStatus
import com.intellij.polySymbols.PolySymbolKind
import com.intellij.polySymbols.PolySymbolModifier
import com.intellij.polySymbols.PolySymbolProperty
import com.intellij.polySymbols.PolySymbolQualifiedName
import com.intellij.polySymbols.query.PolySymbolNameConversionRules
import com.intellij.polySymbols.query.PolySymbolScope
import com.intellij.polySymbols.webTypes.filters.PolySymbolFilter
import com.intellij.psi.PsiElement
import com.intellij.psi.createSmartPointer
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon

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
  /**
   * Direct access to a custom [PolySymbolPatternSymbolsResolver]. Mutually
   * exclusive with the [symbols] block; set this when you have a hand-rolled
   * resolver that does not map to [PolySymbolPatternReferenceResolver].
   */
  @ApiStatus.Internal
  var symbolsResolver: PolySymbolPatternSymbolsResolver? = null

  private var matchPropertyOverrides: MatchPropertyOverridesBuilder? = null
  private val additionalScopes: MutableList<PolySymbolScope> = mutableListOf()

  internal var required: Boolean = true
  internal open val repeats: Boolean get() = false
  internal open val unique: Boolean get() = false

  private var symbolsBuilder: SymbolsBuilder? = null
  private val alternatives: MutableList<PolySymbolPattern> = mutableListOf()

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
      additionalScope = additionalScopes.toList(),
      apiStatus = apiStatus,
      isRequired = required,
      priority = priority,
      repeats = repeats,
      unique = repeats && unique,
      symbolsResolver = resolver,
      additionalLastSegmentSymbol = matchPropertyOverrides?.build(),
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

@PolySymbolPatternDsl
class MatchPropertyOverridesBuilder internal constructor() {

  private val depPointers: MutableList<Pointer<out Any>> = mutableListOf()
  private var priorityGetter: (DependencyScope.() -> PolySymbol.Priority?)? = null
  private var apiStatusGetter: (DependencyScope.() -> PolySymbolApiStatus)? = null
  private var modifiersGetter: (DependencyScope.() -> Set<PolySymbolModifier>)? = null
  private var iconGetter: (DependencyScope.() -> Icon?)? = null
  private val propertyGetters: MutableMap<PolySymbolProperty<*>, DependencyScope.() -> Any?> = mutableMapOf()

  /**
   * Declare a [PsiElement] dependency.
   */
  fun dependency(element: PsiElement): DependencyHandle<PsiElement> =
    registerDep(element.createSmartPointer())

  /** Declare a raw [Pointer] dependency.
   *  Should be used for [PolySymbol] pointers as well. */
  fun <T : Any> dependency(pointer: Pointer<out T>): DependencyHandle<T> =
    registerDep(pointer)

  @Suppress("UNCHECKED_CAST")
  private fun <T : Any> registerDep(pointer: Pointer<out T>): DependencyHandle<T> {
    val idx = depPointers.size
    depPointers += pointer as Pointer<out Any>
    return DependencyHandle(idx)
  }

  /** Override [PolySymbol.priority] on the resulting match. */
  fun priority(provider: DependencyScope.() -> PolySymbol.Priority?) {
    checkNoPsiCapture(provider, "priority")
    priorityGetter = provider
  }

  /** Override [PolySymbol.apiStatus] on the resulting match. */
  fun apiStatus(provider: DependencyScope.() -> PolySymbolApiStatus) {
    checkNoPsiCapture(provider, "apiStatus")
    apiStatusGetter = provider
  }

  /** Override [PolySymbol.modifiers] on the resulting match. */
  fun modifiers(provider: DependencyScope.() -> Set<PolySymbolModifier>) {
    checkNoPsiCapture(provider, "modifiers")
    modifiersGetter = provider
  }

  /** Override [PolySymbol.icon] on the resulting match. */
  fun icon(provider: DependencyScope.() -> Icon?) {
    checkNoPsiCapture(provider, "icon")
    iconGetter = provider
  }

  /** Override a custom [PolySymbolProperty] on the resulting match. */
  fun <T : Any> property(property: PolySymbolProperty<T>, provider: DependencyScope.() -> T?) {
    checkNoPsiCapture(provider, "property[${property.name}]")
    propertyGetters[property] = provider
  }

  /**
   * Resolve all declared dependencies and build the override symbol. Returns
   * `null` if no overrides were set, or if any declared dependency failed to
   * dereference (in which case the whole override contribution is dropped).
   */
  internal fun build(): PolySymbol? {
    if (priorityGetter == null
        && apiStatusGetter == null
        && modifiersGetter == null
        && iconGetter == null
        && propertyGetters.isEmpty()) {
      return null
    }
    val resolved = resolveSnapshot() ?: return null

    return MatchPropertyOverrideSymbol(
      depPointers = depPointers.toList(),
      scope = DependencyScope(resolved),
      priorityGetter = priorityGetter,
      apiStatusGetter = apiStatusGetter,
      modifiersGetter = modifiersGetter,
      iconGetter = iconGetter,
      propertyGetters = propertyGetters.toMap(),
    )
  }

  internal fun resolveSnapshot(): List<Any>? {
    if (depPointers.isEmpty()) return emptyList()
    val snapshot = ArrayList<Any>(depPointers.size)
    for (pointer in depPointers) {
      snapshot += pointer.dereference() ?: run {
        LOG.error("Failed to dereference dependency pointer ($pointer) within the same ReadAction as the pointer was created.")
        return null
      }
    }
    return snapshot
  }
}

/**
 * Opaque handle to a dependency declared in [MatchPropertyOverridesBuilder.dependency].
 * Read the current value with `handle.value` inside an [DependencyScope]
 * receiver — `.value` is guaranteed non-null because the enclosing override
 * symbol is only materialized when every declared dependency is live.
 */
@Suppress("unused") // T is a phantom type used by DependencyScope.value to preserve typing
class DependencyHandle<T : Any> internal constructor(internal val index: Int)

/**
 * Receiver for `overrideMatchProperties` getter lambdas. Provides
 * non-null access to previously declared [DependencyHandle]s.
 */
@PolySymbolPatternDsl
class DependencyScope internal constructor(private val resolved: List<Any>) {
  @Suppress("UNCHECKED_CAST")
  val <T : Any> DependencyHandle<T>.value: T
    get() = resolved[index] as T
}

private val MATCH_PROPERTY_OVERRIDE_KIND: PolySymbolKind =
  PolySymbolKind["", "\$matchPropertyOverride$"]

private val PSI_OR_POLY_SYMBOL_TYPES = arrayOf(
  PsiElement::class.java,
  PolySymbol::class.java,
)

private val LOG = logger<MatchPropertyOverridesBuilder>()

/**
 * In dev/test builds, reflect on [lambda] and fail fast if any captured field
 * holds a [PsiElement] or [PolySymbol] directly — those are only valid within
 * one read action, so they must go through `dependency(...)` to be wrapped as
 * pointers.
 */
private fun checkNoPsiCapture(lambda: Any, context: String) {
  val app = ApplicationManager.getApplication() ?: return
  if (!app.isUnitTestMode && !app.isInternal && !app.isEAP) return
  for (field in lambda::class.java.declaredFields) {
    val fieldType = field.type
    for (forbidden in PSI_OR_POLY_SYMBOL_TYPES) {
      if (forbidden.isAssignableFrom(fieldType)) {
        LOG.error(
          "overrideMatchProperties.$context lambda captures a ${forbidden.simpleName} " +
          "(${fieldType.name} as field ${field.name}). Declare it with dependency(...) " +
          "so it survives read-action boundaries."
        )
      }
    }
  }
}

private class MatchPropertyOverrideSymbol(
  private val depPointers: List<Pointer<out Any>>,
  private val scope: DependencyScope,
  private val priorityGetter: (DependencyScope.() -> PolySymbol.Priority?)?,
  private val apiStatusGetter: (DependencyScope.() -> PolySymbolApiStatus)?,
  private val modifiersGetter: (DependencyScope.() -> Set<PolySymbolModifier>)?,
  private val iconGetter: (DependencyScope.() -> Icon?)?,
  private val propertyGetters: Map<PolySymbolProperty<*>, DependencyScope.() -> Any?>,
) : PolySymbol {
  override val kind: PolySymbolKind get() = MATCH_PROPERTY_OVERRIDE_KIND
  override val name: String get() = ""

  override val priority: PolySymbol.Priority?
    get() = priorityGetter?.invoke(scope) ?: super.priority

  override val apiStatus: PolySymbolApiStatus
    get() = apiStatusGetter?.invoke(scope) ?: super.apiStatus

  override val modifiers: Set<PolySymbolModifier>
    get() = modifiersGetter?.invoke(scope) ?: super.modifiers

  override val icon: Icon?
    get() = iconGetter?.invoke(scope) ?: super.icon

  override fun <T : Any> get(property: PolySymbolProperty<T>): T? {
    val getter = propertyGetters[property] ?: return super.get(property)
    @Suppress("UNCHECKED_CAST")
    return property.tryCast((getter as DependencyScope.() -> T?).invoke(scope))
  }

  override fun createPointer(): Pointer<out PolySymbol> {
    if (depPointers.isEmpty()) return Pointer.hardPointer(this)
    val depPointers = depPointers
    val priorityGetter = priorityGetter
    val apiStatusGetter = apiStatusGetter
    val modifiersGetter = modifiersGetter
    val iconGetter = iconGetter
    val propertyGetters = propertyGetters
    return Pointer {
      val snapshot = ArrayList<Any>(depPointers.size)
      for (pointer in depPointers) {
        snapshot += pointer.dereference() ?: return@Pointer null
      }
      MatchPropertyOverrideSymbol(
        depPointers = depPointers,
        scope = DependencyScope(snapshot),
        priorityGetter = priorityGetter,
        apiStatusGetter = apiStatusGetter,
        modifiersGetter = modifiersGetter,
        iconGetter = iconGetter,
        propertyGetters = propertyGetters,
      )
    }
  }
}
