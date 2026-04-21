// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.dsl

import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.PolySymbolApiStatus
import com.intellij.polySymbols.PolySymbolKind
import com.intellij.polySymbols.PolySymbolModifier
import com.intellij.polySymbols.PolySymbolProperty
import com.intellij.polySymbols.context.PolyContext
import com.intellij.polySymbols.documentation.PolySymbolDocumentationBuilder
import com.intellij.polySymbols.documentation.PolySymbolDocumentationTarget
import com.intellij.polySymbols.patterns.PolySymbolPattern
import com.intellij.polySymbols.patterns.PolySymbolPatternBuilder
import com.intellij.polySymbols.patterns.polySymbolPattern
import com.intellij.polySymbols.query.PolySymbolWithPattern
import com.intellij.polySymbols.refactoring.PolySymbolRenameTarget
import com.intellij.polySymbols.search.PolySymbolSearchTarget
import com.intellij.polySymbols.search.PsiSourcedPolySymbol
import com.intellij.polySymbols.utils.PolySymbolDeclaredInPsi
import com.intellij.psi.PsiElement
import javax.swing.Icon

/**
 * Builds a [PolySymbol] with a declarative, read-action-safe DSL.
 *
 * The produced instance additionally implements one of
 * [PolySymbolWithPattern], [PsiSourcedPolySymbol], or [PolySymbolDeclaredInPsi]
 * when [PolySymbolBuilder.pattern], [PolySymbolBuilder.linkWithPsiElement], or
 * [PolySymbolBuilder.declaredInPsi] is called inside the [body]. Calling any
 * two of those mode methods throws [IllegalStateException].
 *
 * It is advised to use this builder only when creating a few symbol instances at once.
 * Builder and created symbols may be heavy on memory usage, so in case of large numbers of symbols
 * it is recommended to create a dedicated class implementing [PolySymbol] interface.
 */
fun polySymbol(
  kind: PolySymbolKind,
  name: String,
  body: PolySymbolBuilder.() -> Unit,
): BuiltPolySymbol =
  PolySymbolBuilder(kind, name).apply(body).build()

interface BuiltPolySymbol: PolySymbol {

  operator fun <T : Any> get(handle: DependencyHandle<T>): T

}

/** Value returned by the lambda form of [PolySymbolBuilder.declaredInPsi]. */
data class PolySymbolDeclarationSite(
  val sourceElement: PsiElement,
  val textRangeInSourceElement: TextRange,
)

@PolySymbolDsl
class PolySymbolBuilder internal constructor(
  internal val kind: PolySymbolKind,
  internal val name: String,
) : PolySymbolDslBuilderBase() {

  override val builderContext: String get() = "polySymbol"

  internal var extensionValue: Boolean? = null
  internal var extensionGetter: (() -> Boolean)? = null

  internal var psiContextGetter: (() -> PsiElement?)? = null

  internal var presentationValue: TargetPresentation? = null
  internal var presentationGetter: (() -> TargetPresentation)? = null

  internal var isSearchTarget: Boolean = false
  internal var isRenameTarget: Boolean = false

  internal var documentationBuilder: (PolySymbolDocumentationBuilder.(symbol: BuiltPolySymbol, location: PsiElement?) -> Unit)? = null

  internal var navigationTargetsGetter: ((Project) -> Collection<NavigationTarget>)? = null

  internal var matchContextGetter: ((PolyContext) -> Boolean)? = null
  internal var isEquivalentToGetter: ((Symbol) -> Boolean)? = null

  private var mode: Mode = Mode.NONE
  internal var patternBuilder: (PolySymbolPatternBuilder.() -> Unit)? = null
  internal var sourceGetter: (() -> PsiElement?)? = null
  internal var declarationSiteGetter: (() -> PolySymbolDeclarationSite?)? = null

  /**
   * @see [PolySymbol.extension]
   */
  fun extension(value: Boolean) {
    extensionValue = value
    extensionGetter = null
  }

  /**
   * @see [PolySymbol.extension]
   */
  fun extension(provider: () -> Boolean) {
    checkNoPsiCapture(provider, "polySymbol.extension")
    extensionGetter = provider
  }

  /**
   * Override the symbol's [PolySymbol.psiContext].
   *
   * Only meaningful when neither [linkWithPsiElement] nor [declaredInPsi]
   * is used — those modes already wire [PolySymbol.psiContext] to [PsiSourcedPolySymbol.source]/
   * [PolySymbolDeclaredInPsi.sourceElement] respectively. Calling this setter in either of those
   * modes throws [IllegalStateException] at `build()`.
   *
   * @see [PolySymbol.psiContext]
   */
  fun psiContext(value: PsiElement?) {
    if (value == null) {
      psiContextGetter = { null }
    }
    else {
      val handle = dependency(value)
      psiContextGetter = { handle.readInScope() }
    }
  }

  /**
   * Override the symbol's [PolySymbol.psiContext].
   *
   * Only meaningful when neither [linkWithPsiElement] nor [declaredInPsi]
   * is used — those modes already wire [PolySymbol.psiContext] to [PsiSourcedPolySymbol.source]/
   * [PolySymbolDeclaredInPsi.sourceElement] respectively. Calling this setter in either of those
   * modes throws [IllegalStateException] at `build()`.
   *
   * @see [PolySymbol.psiContext]
   */
  fun psiContext(provider: () -> PsiElement?) {
    checkNoPsiCapture(provider, "polySymbol.psiContext")
    psiContextGetter = provider
  }

  /**
   * @see [PolySymbol.presentation]
   */
  fun presentation(value: TargetPresentation) {
    presentationValue = value
    presentationGetter = null
  }

  /**
   * @see [PolySymbol.presentation]
   */
  fun presentation(provider: () -> TargetPresentation) {
    checkNoPsiCapture(provider, "polySymbol.presentation")
    presentationGetter = provider
  }

  /**
   * Marks this symbol as search target using generic [PolySymbolSearchTarget.create].
   *
   * @see [PolySymbol.searchTarget]
   */
  fun searchTarget() {
    isSearchTarget = true
  }

  /**
   * Marks this symbol as rename target using generic [PolySymbolRenameTarget.create].
   *
   * @see [PolySymbol.renameTarget]
   */
  fun renameTarget() {
    isRenameTarget = true
  }

  /**
   * Provide symbol's documentation through a [PolySymbolDocumentationBuilder].
   *
   * To get a value of a handler created with [dependency] method, access it with `get` operator
   * on the provided [BuiltPolySymbol] instance:
   * ```kotlin
   *
   * val variable: JSVariable // JSVariable is a PsiElement
   *
   * polySymbol(JS_SYMBOLS, "variable") {
   *   val variable by dependency(variable)
   *   documentation { symbol, location ->
   *     val type = symbol[variable].jsType
   *   }
   * }
   * ```
   * @see [PolySymbol.getDocumentationTarget]
   */
  fun documentation(
    builder: PolySymbolDocumentationBuilder.(symbol: BuiltPolySymbol, location: PsiElement?) -> Unit,
  ) {
    checkNoPsiCapture(builder, "polySymbol.documentation")
    documentationBuilder = builder
  }

  /**
   * @see [PolySymbol.getNavigationTargets]
   */
  fun navigationTargets(provider: (project: Project) -> Collection<NavigationTarget>) {
    checkNoPsiCapture(provider, "polySymbol.navigationTargets")
    navigationTargetsGetter = provider
  }

  /**
   * @see [PolySymbol.matchContext]
   */
  fun matchContext(provider: (context: PolyContext) -> Boolean) {
    checkNoPsiCapture(provider, "polySymbol.matchContext")
    matchContextGetter = provider
  }

  /**
   * Provides additional [PolySymbol.isEquivalentTo] implementation.
   *
   * @see [PolySymbol.isEquivalentTo]
   */
  fun isEquivalentTo(provider: (symbol: Symbol) -> Boolean) {
    checkNoPsiCapture(provider, "polySymbol.isEquivalentTo")
    isEquivalentToGetter = provider
  }

  /**
   * Attach a pattern. The lambda runs lazily inside the materialized symbol's
   * dependency scope, so it may reference `by dependency(...)` handles
   * declared on this builder.
   *
   * @see [PolySymbolWithPattern]
   * @see [polySymbolPattern]
   */
  fun pattern(body: PolySymbolPatternBuilder.() -> Unit) {
    enterMode(Mode.PATTERN)
    checkNoPsiCapture(body, "polySymbol.pattern")
    patternBuilder = body
  }

  /**
   * Define a [PsiSourcedPolySymbol].
   *
   * @see [PsiSourcedPolySymbol]
   */
  fun linkWithPsiElement(element: PsiElement) {
    enterMode(Mode.LINK_WITH_PSI)
    val handle = dependency(element)
    sourceGetter = { handle.readInScope() }
  }

  /**
   * Define a [PsiSourcedPolySymbol].
   *
   * @see [PsiSourcedPolySymbol]
   */
  fun linkWithPsiElement(provider: () -> PsiElement?) {
    enterMode(Mode.LINK_WITH_PSI)
    checkNoPsiCapture(provider, "polySymbol.linkWithPsiElement")
    sourceGetter = provider
  }

  /**
   * Define a [PolySymbolDeclaredInPsi].
   *
   * @see [PolySymbolDeclaredInPsi]
   */
  fun declaredInPsi(element: PsiElement, range: TextRange) {
    enterMode(Mode.DECLARED_IN_PSI)
    val handle = dependency(element)
    declarationSiteGetter = {
      PolySymbolDeclarationSite(handle.readInScope(), range)
    }
  }

  /**
   * Define a [PolySymbolDeclaredInPsi].
   *
   * @see [PolySymbolDeclaredInPsi]
   */
  fun declaredInPsi(provider: () -> PolySymbolDeclarationSite?) {
    enterMode(Mode.DECLARED_IN_PSI)
    checkNoPsiCapture(provider, "polySymbol.declaredInPsi")
    declarationSiteGetter = provider
  }

  private fun enterMode(newMode: Mode) {
    if (mode != Mode.NONE && mode != newMode) {
      throw IllegalStateException(
        "symbol is already configured as ${mode.label}; cannot additionally apply ${newMode.label}."
      )
    }
    mode = newMode
  }

  internal fun build(): BuiltPolySymbol {
    // Mutual-exclusion + cross-mode validation.
    if (mode == Mode.LINK_WITH_PSI || mode == Mode.DECLARED_IN_PSI) {
      check(!isSearchTarget) {
        "searchTarget(...) is handled by the framework in ${mode.label} mode; do not override."
      }
      check(!isRenameTarget) {
        "renameTarget(...) is handled by the framework in ${mode.label} mode; do not override."
      }
      check(psiContextGetter == null) {
        "psiContext(...) is wired automatically in ${mode.label} mode; do not override."
      }
    }

    val source: DependencySource =
      if (depSpecs.isEmpty()) EMPTY_DEPENDENCY_SOURCE
      else DependencySource.FromSpecs(depSpecs.toList())

    val initialScope = DependencyScope(resolveSnapshot())
    val config = toBuiltConfig()

    return when (mode) {
      Mode.NONE -> BuiltPolySymbolImpl(
        config, source, initialScope,
        psiContextGetter = psiContextGetter,
      )
      Mode.PATTERN -> BuiltPolySymbolWithPattern(
        config, source, initialScope,
        patternBuilder = patternBuilder ?: error("pattern was not set on PolySymbolBuilder in PATTERN mode"),
        psiContextGetter = psiContextGetter,
      )
      Mode.LINK_WITH_PSI -> BuiltPsiSourcedPolySymbol(
        config, source, initialScope,
        sourceGetter = sourceGetter ?: error("source was not set on PolySymbolBuilder in LINK_WITH_PSI mode"),
      )
      Mode.DECLARED_IN_PSI -> BuiltPolySymbolDeclaredInPsi(
        config, source, initialScope,
        declarationSiteGetter = declarationSiteGetter ?: error("declarationSite was not set on PolySymbolBuilder in DECLARED_IN_PSI mode"),
      )
    }
  }

  internal enum class Mode(val label: String) {
    NONE("plain"),
    PATTERN("pattern"),
    LINK_WITH_PSI("linkWithPsiElement"),
    DECLARED_IN_PSI("declaredInPsi"),
  }
}

private val EMPTY_DEPENDENCY_SOURCE: DependencySource = DependencySource.FromSpecs(emptyList())

private abstract class BuiltPolySymbolBase(
  protected val config: BuiltConfig,
  private val dependencySource: DependencySource,
  protected val dependencyScope: DependencyScope,
) : BuiltPolySymbol {

  protected abstract fun buildConstructor(): (config: BuiltConfig, source: DependencySource, scope: DependencyScope) -> PolySymbol

  override val kind: PolySymbolKind get() = config.kind
  override val name: String get() = config.name

  override val priority: PolySymbol.Priority?
    get() = config.priorityGetter?.let { dependencyScope.withinScope { it() } }
            ?: config.priorityValue
            ?: super.priority

  override val apiStatus: PolySymbolApiStatus
    get() = config.apiStatusGetter?.let { dependencyScope.withinScope { it() } }
            ?: config.apiStatusValue
            ?: super.apiStatus

  override val modifiers: Set<PolySymbolModifier>
    get() = config.modifiersGetter?.let { dependencyScope.withinScope { it() } }
            ?: config.modifiersValue
            ?: super.modifiers

  override val icon: Icon?
    get() = config.iconGetter?.let { dependencyScope.withinScope { it() } }
            ?: config.iconValue
            ?: super.icon

  override val extension: Boolean
    get() = config.extensionGetter?.let { dependencyScope.withinScope { it() } }
            ?: config.extensionValue
            ?: super.extension

  override val presentation: TargetPresentation
    get() = config.presentationGetter?.let { dependencyScope.withinScope { it() } }
            ?: config.presentationValue
            ?: super.presentation

  override val searchTarget: PolySymbolSearchTarget?
    get() = if (config.isSearchTarget) PolySymbolSearchTarget.create(this) else null

  override val renameTarget: PolySymbolRenameTarget?
    get() = if (config.isRenameTarget) PolySymbolRenameTarget.create(this) else null

  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> get(handle: DependencyHandle<T>): T =
    dependencyScope.resolved[handle.index] as T

  override fun getDocumentationTarget(location: PsiElement?): DocumentationTarget? {
    val docBuilder = config.documentationBuilder
    if (docBuilder != null) {
      return PolySymbolDocumentationTarget.create(this, location, docBuilder)
    }
    return super.getDocumentationTarget(location)
  }

  override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
    config.navigationTargetsGetter?.let { dependencyScope.withinScope(project) { it(project) } }
    ?: super.getNavigationTargets(project)

  override fun matchContext(context: PolyContext): Boolean =
    config.matchContextGetter?.let { dependencyScope.withinScope(context) { it(context) } }
    ?: super.matchContext(context)

  override fun isEquivalentTo(symbol: Symbol): Boolean =
    super.isEquivalentTo(symbol)
    || (config.isEquivalentToGetter?.let { dependencyScope.withinScope(symbol) { it(symbol) } }
        ?: false)

  override fun <T : Any> get(property: PolySymbolProperty<T>): T? {
    val getter = config.propertyGetters[property]
    if (getter != null) {
      @Suppress("UNCHECKED_CAST")
      return property.tryCast(dependencyScope.withinScope { (getter as () -> T?).invoke() })
    }
    if (config.propertyValues.containsKey(property)) {
      return property.tryCast(config.propertyValues[property])
    }
    return super.get(property)
  }

  protected fun createPointerImpl(): Pointer<out PolySymbol> {
    if (dependencySource.isEmpty) return Pointer.hardPointer(this)
    val pointerSource = DependencySource.FromPointers(dependencySource.pointers())
    val config = config
    val self: (BuiltConfig, DependencySource, DependencyScope) -> PolySymbol = buildConstructor()
    return Pointer {
      val snapshot = pointerSource.snapshot() ?: return@Pointer null
      self(config, pointerSource, DependencyScope(snapshot))
    }
  }

  override fun equals(other: Any?): Boolean =
    other === this
    || other is BuiltPolySymbolBase
    && other.javaClass == javaClass
    && other.config == config
    && other.dependencyScope.resolved == dependencyScope.resolved

  override fun hashCode(): Int {
    var result = config.hashCode()
    result = 31 * result + dependencyScope.resolved.hashCode()
    return result
  }
}

/**
 * Snapshot of the mode-independent state captured at `build()` time.
 * Mode-specific fields (pattern builder, source getter, declaration site)
 * are passed directly to the matching specialized `BuiltPolySymbol*` class.
 */
private data class BuiltConfig(
  val kind: PolySymbolKind,
  val name: String,
  val priorityValue: PolySymbol.Priority?,
  val priorityGetter: (() -> PolySymbol.Priority?)?,
  val apiStatusValue: PolySymbolApiStatus?,
  val apiStatusGetter: (() -> PolySymbolApiStatus)?,
  val modifiersValue: Set<PolySymbolModifier>?,
  val modifiersGetter: (() -> Set<PolySymbolModifier>)?,
  val iconValue: Icon?,
  val iconGetter: (() -> Icon?)?,
  val extensionValue: Boolean?,
  val extensionGetter: (() -> Boolean)?,
  val presentationValue: TargetPresentation?,
  val presentationGetter: (() -> TargetPresentation)?,
  val isSearchTarget: Boolean,
  val isRenameTarget: Boolean,
  val documentationBuilder: (PolySymbolDocumentationBuilder.(BuiltPolySymbol, PsiElement?) -> Unit)?,
  val navigationTargetsGetter: ((Project) -> Collection<NavigationTarget>)?,
  val matchContextGetter: ((PolyContext) -> Boolean)?,
  val isEquivalentToGetter: ((Symbol) -> Boolean)?,
  val propertyValues: Map<PolySymbolProperty<*>, Any?>,
  val propertyGetters: Map<PolySymbolProperty<*>, () -> Any?>,
)

private fun PolySymbolBuilder.toBuiltConfig(): BuiltConfig = BuiltConfig(
  kind = kind,
  name = name,
  priorityValue = priorityValue,
  priorityGetter = priorityGetter,
  apiStatusValue = apiStatusValue,
  apiStatusGetter = apiStatusGetter,
  modifiersValue = modifiersValue,
  modifiersGetter = modifiersGetter,
  iconValue = iconValue,
  iconGetter = iconGetter,
  extensionValue = extensionValue,
  extensionGetter = extensionGetter,
  presentationValue = presentationValue,
  presentationGetter = presentationGetter,
  isSearchTarget = isSearchTarget,
  isRenameTarget = isRenameTarget,
  documentationBuilder = documentationBuilder,
  navigationTargetsGetter = navigationTargetsGetter,
  matchContextGetter = matchContextGetter,
  isEquivalentToGetter = isEquivalentToGetter,
  propertyValues = propertyValues.toMap(),
  propertyGetters = propertyGetters.toMap(),
)

private open class BuiltPolySymbolImpl(
  config: BuiltConfig,
  dependencySource: DependencySource,
  dependencyScope: DependencyScope,
  protected val psiContextGetter: (() -> PsiElement?)?,
) : BuiltPolySymbolBase(config, dependencySource, dependencyScope) {

  override val psiContext: PsiElement?
    get() = psiContextGetter?.let { this@BuiltPolySymbolImpl.dependencyScope.withinScope { it() } }
            ?: super.psiContext

  override fun buildConstructor(): (config: BuiltConfig, source: DependencySource, scope: DependencyScope) -> PolySymbol {
    val psiContextGetter = psiContextGetter
    return { config, source, scope ->
      BuiltPolySymbolImpl(config, source, scope, psiContextGetter)
    }
  }

  override fun createPointer(): Pointer<out PolySymbol> = createPointerImpl()
}

private class BuiltPolySymbolWithPattern(
  config: BuiltConfig,
  dependencySource: DependencySource,
  dependencyScope: DependencyScope,
  private val patternBuilder: PolySymbolPatternBuilder.() -> Unit,
  psiContextGetter: (() -> PsiElement?)?,
) : BuiltPolySymbolImpl(config, dependencySource, dependencyScope, psiContextGetter), PolySymbolWithPattern {

  // Evaluate the pattern body lazily inside this instance's dependency scope,
  // so that any `by dependency(...)` handles declared on the builder read
  // fresh values in each read action.
  override val pattern: PolySymbolPattern by lazy(LazyThreadSafetyMode.PUBLICATION) {
    dependencyScope.withinScope { polySymbolPattern(patternBuilder) }
  }

  override fun buildConstructor(): (config: BuiltConfig, source: DependencySource, scope: DependencyScope) -> PolySymbol {
    val patternBuilder = patternBuilder
    val psiContextGetter = psiContextGetter
    return { config, source, scope ->
      BuiltPolySymbolWithPattern(config, source, scope, patternBuilder, psiContextGetter)
    }
  }

  override fun equals(other: Any?): Boolean =
    super.equals(other)
    && other is BuiltPolySymbolWithPattern
    && other.patternBuilder == patternBuilder

  override fun hashCode(): Int {
    var result = super.hashCode()
    result = 31 * result + patternBuilder.javaClass.hashCode()
    return result
  }

  override fun createPointer(): Pointer<out PolySymbol> = createPointerImpl()
}

private class BuiltPsiSourcedPolySymbol(
  config: BuiltConfig,
  dependencySource: DependencySource,
  dependencyScope: DependencyScope,
  private val sourceGetter: () -> PsiElement?,
) : BuiltPolySymbolBase(config, dependencySource, dependencyScope), PsiSourcedPolySymbol {

  override val source: PsiElement?
    get() = dependencyScope.withinScope { sourceGetter() }

  // Use PsiSourcedPolySymbol's default navigation / equivalence impls (they
  // read `source`) unless the builder explicitly supplied a custom getter.
  override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
    if (config.navigationTargetsGetter != null)
      super<BuiltPolySymbolBase>.getNavigationTargets(project)
    else
      super<PsiSourcedPolySymbol>.getNavigationTargets(project)

  override fun isEquivalentTo(symbol: Symbol): Boolean =
    super<PsiSourcedPolySymbol>.isEquivalentTo(symbol)
    || super<BuiltPolySymbolBase>.isEquivalentTo(symbol)

  override fun buildConstructor(): (config: BuiltConfig, source: DependencySource, scope: DependencyScope) -> PolySymbol {
    val sourceGetter = sourceGetter
    return { config, source, scope ->
      BuiltPsiSourcedPolySymbol(config, source, scope, sourceGetter)
    }
  }

  override fun equals(other: Any?): Boolean =
    super.equals(other)
    && other is BuiltPsiSourcedPolySymbol
    && other.sourceGetter == sourceGetter

  override fun hashCode(): Int =
    super.hashCode() * 31 + sourceGetter.hashCode()

  @Suppress("UNCHECKED_CAST")
  override fun createPointer(): Pointer<out PsiSourcedPolySymbol> =
    createPointerImpl() as Pointer<out PsiSourcedPolySymbol>
}

private class BuiltPolySymbolDeclaredInPsi(
  config: BuiltConfig,
  source: DependencySource,
  scope: DependencyScope,
  private val declarationSiteGetter: (() -> PolySymbolDeclarationSite?),
) : BuiltPolySymbolBase(config, source, scope), PolySymbolDeclaredInPsi {

  private val declarationSite: PolySymbolDeclarationSite? by lazy(LazyThreadSafetyMode.PUBLICATION) {
    scope.withinScope { declarationSiteGetter() }
  }

  override val sourceElement: PsiElement?
    get() = declarationSite?.sourceElement

  override val textRangeInSourceElement: TextRange?
    get() = declarationSite?.textRangeInSourceElement

  override fun getNavigationTargets(project: Project): Collection<NavigationTarget> =
    if (config.navigationTargetsGetter != null)
      super<BuiltPolySymbolBase>.getNavigationTargets(project)
    else
      super<PolySymbolDeclaredInPsi>.getNavigationTargets(project)


  override fun equals(other: Any?): Boolean =
    super.equals(other)
    && other is BuiltPolySymbolDeclaredInPsi
    && other.declarationSiteGetter == declarationSiteGetter

  override fun hashCode(): Int =
    super.hashCode() * 31 + declarationSiteGetter.hashCode()

  override fun buildConstructor(): (config: BuiltConfig, source: DependencySource, scope: DependencyScope) -> PolySymbol {
    val sourceGetter = declarationSiteGetter
    return { config, source, scope ->
      BuiltPolySymbolDeclaredInPsi(config, source, scope, sourceGetter)
    }
  }

  @Suppress("UNCHECKED_CAST")
  override fun createPointer(): Pointer<out PolySymbolDeclaredInPsi> =
    createPointerImpl() as Pointer<out PolySymbolDeclaredInPsi>
}
