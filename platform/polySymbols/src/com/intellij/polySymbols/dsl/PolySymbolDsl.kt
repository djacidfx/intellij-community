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
 * Read-action semantics are identical to `overrideMatchProperties { }` in the
 * pattern DSL: dependencies declared via `dependency(element | symbol)` are
 * wrapped in pointers lazily (only when the symbol's own `createPointer()` is
 * invoked), then dereferenced fresh on every cross-read-action revival.
 */
fun buildPolySymbol(
  kind: PolySymbolKind,
  name: String,
  body: PolySymbolBuilder.() -> Unit,
): PolySymbol =
  PolySymbolBuilder(kind, name).apply(body).build()

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

  override val builderContext: String get() = "buildPolySymbol"

  // === Storage ===

  internal var extensionValue: Boolean? = null
  internal var extensionGetter: (() -> Boolean)? = null

  internal var psiContextGetter: (() -> PsiElement?)? = null

  internal var presentationValue: TargetPresentation? = null
  internal var presentationGetter: (() -> TargetPresentation)? = null

  internal var searchTargetSelf: Boolean = false
  internal var searchTargetGetter: (() -> PolySymbolSearchTarget?)? = null

  internal var renameTargetSelf: Boolean = false
  internal var renameTargetGetter: (() -> PolySymbolRenameTarget?)? = null

  internal var documentationTargetGetter: ((PsiElement?) -> DocumentationTarget?)? = null
  internal var documentationBuilder: (PolySymbolDocumentationBuilder.(symbol: PolySymbol, location: PsiElement?) -> Unit)? = null

  internal var navigationTargetsGetter: ((Project) -> Collection<NavigationTarget>)? = null

  internal var matchContextGetter: ((PolyContext) -> Boolean)? = null
  internal var isEquivalentToGetter: ((Symbol) -> Boolean)? = null

  private var mode: Mode = Mode.NONE
  internal var patternBuilder: (PolySymbolPatternBuilder.() -> Unit)? = null
  internal var sourceGetter: (() -> PsiElement?)? = null
  internal var declarationSiteGetter: (() -> PolySymbolDeclarationSite?)? = null

  // === Additional scalar overrides ===

  fun extension(value: Boolean) {
    extensionValue = value
    extensionGetter = null
  }

  fun extension(provider: () -> Boolean) {
    checkNoPsiCapture(provider, "buildPolySymbol.extension")
    extensionGetter = provider
  }

  /**
   * Override the symbol's `psiContext`.
   *
   * Only meaningful when neither `linkWithPsiElement(…)` nor `declaredInPsi(…)`
   * is used — those modes already wire `psiContext` to `source` /
   * `sourceElement` respectively. Calling this setter in either of those
   * modes throws `IllegalStateException` at `build()`.
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

  fun psiContext(provider: () -> PsiElement?) {
    checkNoPsiCapture(provider, "buildPolySymbol.psiContext")
    psiContextGetter = provider
  }

  fun presentation(value: TargetPresentation) {
    presentationValue = value
    presentationGetter = null
  }

  fun presentation(provider: () -> TargetPresentation) {
    checkNoPsiCapture(provider, "buildPolySymbol.presentation")
    presentationGetter = provider
  }

  /**
   * Shortcut that wires `searchTarget = PolySymbolSearchTarget.create(this)`
   * where `this` is the freshly-materialized symbol (per read action).
   */
  fun searchTarget() {
    searchTargetSelf = true
    searchTargetGetter = null
  }

  fun searchTarget(provider: () -> PolySymbolSearchTarget?) {
    checkNoPsiCapture(provider, "buildPolySymbol.searchTarget")
    searchTargetSelf = false
    searchTargetGetter = provider
  }

  /**
   * Shortcut that wires `renameTarget = PolySymbolRenameTarget.create(this)`
   * where `this` is the freshly-materialized symbol (per read action).
   */
  fun renameTarget() {
    renameTargetSelf = true
    renameTargetGetter = null
  }

  fun renameTarget(provider: () -> PolySymbolRenameTarget?) {
    checkNoPsiCapture(provider, "buildPolySymbol.renameTarget")
    renameTargetSelf = false
    renameTargetGetter = provider
  }

  /**
   * Override `getDocumentationTarget(location)`.
   * Mutually exclusive with [documentation] — calling both throws
   * `IllegalStateException` at `build()`.
   */
  fun documentationTarget(provider: (location: PsiElement?) -> DocumentationTarget?) {
    checkNoPsiCapture(provider, "buildPolySymbol.documentationTarget")
    documentationTargetGetter = provider
  }

  /**
   * Shortcut for `PolySymbolDocumentationTarget.create(this, location) { … }`.
   * The lambda receives the freshly-materialized symbol and location, and
   * configures a [PolySymbolDocumentationBuilder].
   *
   * Mutually exclusive with [documentationTarget] — calling both throws
   * `IllegalStateException` at `build()`.
   */
  fun documentation(
    builder: PolySymbolDocumentationBuilder.(symbol: PolySymbol, location: PsiElement?) -> Unit,
  ) {
    checkNoPsiCapture(builder, "buildPolySymbol.documentation")
    documentationBuilder = builder
  }

  fun navigationTargets(provider: (project: Project) -> Collection<NavigationTarget>) {
    checkNoPsiCapture(provider, "buildPolySymbol.navigationTargets")
    navigationTargetsGetter = provider
  }

  fun matchContext(provider: (context: PolyContext) -> Boolean) {
    checkNoPsiCapture(provider, "buildPolySymbol.matchContext")
    matchContextGetter = provider
  }

  fun isEquivalentTo(provider: (symbol: Symbol) -> Boolean) {
    checkNoPsiCapture(provider, "buildPolySymbol.isEquivalentTo")
    isEquivalentToGetter = provider
  }

  // === Mode-toggling methods ===

  /**
   * Attach a pattern. The lambda runs lazily inside the materialized symbol's
   * dependency scope, so it may reference `by dependency(...)` handles
   * declared on this builder.
   */
  fun pattern(body: PolySymbolPatternBuilder.() -> Unit) {
    enterMode(Mode.PATTERN)
    checkNoPsiCapture(body, "buildPolySymbol.pattern")
    patternBuilder = body
  }

  fun linkWithPsiElement(element: PsiElement) {
    enterMode(Mode.LINK_WITH_PSI)
    val handle = dependency(element)
    sourceGetter = { handle.readInScope() }
  }

  fun linkWithPsiElement(provider: () -> PsiElement?) {
    enterMode(Mode.LINK_WITH_PSI)
    checkNoPsiCapture(provider, "buildPolySymbol.linkWithPsiElement")
    sourceGetter = provider
  }

  fun declaredInPsi(element: PsiElement, range: TextRange) {
    enterMode(Mode.DECLARED_IN_PSI)
    val handle = dependency(element)
    declarationSiteGetter = {
      PolySymbolDeclarationSite(handle.readInScope(), range)
    }
  }

  fun declaredInPsi(provider: () -> PolySymbolDeclarationSite?) {
    enterMode(Mode.DECLARED_IN_PSI)
    checkNoPsiCapture(provider, "buildPolySymbol.declaredInPsi")
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

  // === Build ===

  internal fun build(): PolySymbol {
    // Mutual-exclusion + cross-mode validation.
    check(documentationTargetGetter == null || documentationBuilder == null) {
      "documentationTarget and documentation are mutually exclusive — pick one."
    }
    if (mode == Mode.LINK_WITH_PSI || mode == Mode.DECLARED_IN_PSI) {
      check(searchTargetGetter == null && !searchTargetSelf) {
        "searchTarget(...) is handled by the framework in ${mode.label} mode; do not override."
      }
      check(renameTargetGetter == null && !renameTargetSelf) {
        "renameTarget(...) is handled by the framework in ${mode.label} mode; do not override."
      }
      check(psiContextGetter == null) {
        "psiContext(...) is wired automatically in ${mode.label} mode; do not override."
      }
    }

    val source: DependencySource =
      if (depSpecs.isEmpty()) EMPTY_DEPENDENCY_SOURCE
      else DependencySource.FromSpecs(depSpecs.toList())

    val initialScope = DependencyScope(resolveSnapshot() ?: emptyList())
    val config = toBuiltConfig()

    return when (mode) {
      Mode.NONE -> BuiltPolySymbol(
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

// ===========================================================================
// Private symbol implementations
// ===========================================================================

private abstract class BuiltPolySymbolBase(
  protected val config: BuiltConfig,
  private val dependencySource: DependencySource,
  protected val dependencyScope: DependencyScope,
) : PolySymbol {

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
    get() = when {
      config.searchTargetSelf -> PolySymbolSearchTarget.create(this)
      else -> config.searchTargetGetter?.let { dependencyScope.withinScope { it() } }
              ?: super.searchTarget
    }

  override val renameTarget: PolySymbolRenameTarget?
    get() = when {
      config.renameTargetSelf -> PolySymbolRenameTarget.create(this)
      else -> config.renameTargetGetter?.let { dependencyScope.withinScope { it() } }
              ?: super.renameTarget
    }

  override fun getDocumentationTarget(location: PsiElement?): DocumentationTarget? {
    val docBuilder = config.documentationBuilder
    if (docBuilder != null) {
      return PolySymbolDocumentationTarget.create(this, location, docBuilder)
    }
    val getter = config.documentationTargetGetter
    if (getter != null) {
      return dependencyScope.withinScope(location) { getter(it) }
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
    config.isEquivalentToGetter?.let { dependencyScope.withinScope(symbol) { it(symbol) } }
    ?: super.isEquivalentTo(symbol)

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
  val searchTargetSelf: Boolean,
  val searchTargetGetter: (() -> PolySymbolSearchTarget?)?,
  val renameTargetSelf: Boolean,
  val renameTargetGetter: (() -> PolySymbolRenameTarget?)?,
  val documentationTargetGetter: ((PsiElement?) -> DocumentationTarget?)?,
  val documentationBuilder: (PolySymbolDocumentationBuilder.(PolySymbol, PsiElement?) -> Unit)?,
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
  searchTargetSelf = searchTargetSelf,
  searchTargetGetter = searchTargetGetter,
  renameTargetSelf = renameTargetSelf,
  renameTargetGetter = renameTargetGetter,
  documentationTargetGetter = documentationTargetGetter,
  documentationBuilder = documentationBuilder,
  navigationTargetsGetter = navigationTargetsGetter,
  matchContextGetter = matchContextGetter,
  isEquivalentToGetter = isEquivalentToGetter,
  propertyValues = propertyValues.toMap(),
  propertyGetters = propertyGetters.toMap(),
)

private open class BuiltPolySymbol(
  config: BuiltConfig,
  dependencySource: DependencySource,
  dependencyScope: DependencyScope,
  protected val psiContextGetter: (() -> PsiElement?)?,
) : BuiltPolySymbolBase(config, dependencySource, dependencyScope) {

  override val psiContext: PsiElement?
    get() = psiContextGetter?.let { this@BuiltPolySymbol.dependencyScope.withinScope { it() } }
            ?: super.psiContext

  override fun buildConstructor(): (config: BuiltConfig, source: DependencySource, scope: DependencyScope) -> PolySymbol {
    val psiContextGetter = psiContextGetter
    return { config, source, scope ->
      BuiltPolySymbol(config, source, scope, psiContextGetter)
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
) : BuiltPolySymbol(config, dependencySource, dependencyScope, psiContextGetter), PolySymbolWithPattern {

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
    if (config.isEquivalentToGetter != null)
      super<BuiltPolySymbolBase>.isEquivalentTo(symbol)
    else
      super<PsiSourcedPolySymbol>.isEquivalentTo(symbol)

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
