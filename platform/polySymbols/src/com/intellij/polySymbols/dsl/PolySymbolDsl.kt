// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.dsl

import com.intellij.model.Symbol
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.PolySymbolKind
import com.intellij.polySymbols.context.PolyContext
import com.intellij.polySymbols.documentation.PolySymbolDocumentationBuilder
import com.intellij.polySymbols.dsl.impl.PolySymbolBuilderImpl
import com.intellij.polySymbols.patterns.PolySymbolPatternBuilder
import com.intellij.polySymbols.patterns.polySymbolPattern
import com.intellij.polySymbols.query.PolySymbolWithPattern
import com.intellij.polySymbols.search.PsiSourcedPolySymbol
import com.intellij.polySymbols.utils.PolySymbolDeclaredInPsi
import com.intellij.psi.PsiElement
import org.jetbrains.annotations.ApiStatus

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
  PolySymbolBuilderImpl(kind, name).apply(body).build()

@ApiStatus.NonExtendable
interface BuiltPolySymbol: PolySymbol {

  operator fun <T : Any> get(handle: DependencyHandle<T>): T

}

/** Value returned by the lambda form of [PolySymbolBuilder.declaredInPsi]. */
data class PolySymbolDeclarationSite(
  val sourceElement: PsiElement,
  val textRangeInSourceElement: TextRange,
)

@PolySymbolDsl
@ApiStatus.NonExtendable
interface PolySymbolBuilder : PolySymbolDslBuilderBase {

  /**
   * @see [PolySymbol.extension]
   */
  fun extension(value: Boolean)

  /**
   * @see [PolySymbol.extension]
   */
  fun extension(provider: () -> Boolean)

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
  fun psiContext(value: PsiElement?)

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
  fun psiContext(provider: () -> PsiElement?)

  /**
   * @see [PolySymbol.presentation]
   */
  fun presentation(value: TargetPresentation)

  /**
   * @see [PolySymbol.presentation]
   */
  fun presentation(provider: () -> TargetPresentation)

  /**
   * Marks this symbol as search target using generic
   * [com.intellij.polySymbols.search.PolySymbolSearchTarget.create].
   *
   * @see [PolySymbol.searchTarget]
   */
  fun searchTarget()

  /**
   * Marks this symbol as rename target using generic
   * [com.intellij.polySymbols.refactoring.PolySymbolRenameTarget.create].
   *
   * @see [PolySymbol.renameTarget]
   */
  fun renameTarget()

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
  )

  /**
   * @see [PolySymbol.getNavigationTargets]
   */
  fun navigationTargets(provider: (project: Project) -> Collection<NavigationTarget>)

  /**
   * @see [PolySymbol.matchContext]
   */
  fun matchContext(provider: (context: PolyContext) -> Boolean)

  /**
   * Provides additional [PolySymbol.isEquivalentTo] implementation.
   *
   * @see [PolySymbol.isEquivalentTo]
   */
  fun isEquivalentTo(provider: (symbol: Symbol) -> Boolean)

  /**
   * Attach a pattern. The lambda runs lazily inside the materialized symbol's
   * dependency scope, so it may reference `by dependency(...)` handles
   * declared on this builder.
   *
   * @see [PolySymbolWithPattern]
   * @see [polySymbolPattern]
   */
  fun pattern(body: PolySymbolPatternBuilder.() -> Unit)

  /**
   * Define a [PsiSourcedPolySymbol].
   *
   * @see [PsiSourcedPolySymbol]
   */
  fun linkWithPsiElement(element: PsiElement)

  /**
   * Define a [PsiSourcedPolySymbol].
   *
   * @see [PsiSourcedPolySymbol]
   */
  fun linkWithPsiElement(provider: () -> PsiElement?)

  /**
   * Define a [PolySymbolDeclaredInPsi].
   *
   * @see [PolySymbolDeclaredInPsi]
   */
  fun declaredInPsi(element: PsiElement, range: TextRange)

  /**
   * Define a [PolySymbolDeclaredInPsi].
   *
   * @see [PolySymbolDeclaredInPsi]
   */
  fun declaredInPsi(provider: () -> PolySymbolDeclarationSite?)
}
