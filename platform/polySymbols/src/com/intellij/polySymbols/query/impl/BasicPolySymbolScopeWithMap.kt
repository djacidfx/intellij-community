// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.query.impl

import com.intellij.model.Pointer
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.PolySymbolBuilder
import com.intellij.polySymbols.PolySymbolKind
import com.intellij.polySymbols.PolySymbolQualifiedName
import com.intellij.polySymbols.completion.PolySymbolCodeCompletionItem
import com.intellij.polySymbols.impl.SearchMap
import com.intellij.polySymbols.impl.checkNoPsiCapture
import com.intellij.polySymbols.polySymbol
import com.intellij.polySymbols.query.PolySymbolCodeCompletionQueryParams
import com.intellij.polySymbols.query.PolySymbolListSymbolsQueryParams
import com.intellij.polySymbols.query.PolySymbolNameMatchQueryParams
import com.intellij.polySymbols.query.PolySymbolNamesProvider
import com.intellij.polySymbols.query.PolySymbolQueryParams
import com.intellij.polySymbols.query.PolySymbolQueryStack
import com.intellij.polySymbols.query.PolySymbolScope
import com.intellij.polySymbols.query.PolySymbolScopeBuilder
import com.intellij.polySymbols.query.PolySymbolWithPattern
import com.intellij.polySymbols.utils.qualifiedName
import com.intellij.util.containers.ContainerUtil
import java.util.concurrent.ConcurrentMap

internal class BasicPolySymbolScopeWithMap(
  private val providesKinds: Set<PolySymbolKind>,
  private val providesPredicate: ((PolySymbolKind) -> Boolean)?,
  private val symbols: List<PolySymbol>,
  private val codeCompletionFilter: ((PolySymbolKind, List<PolySymbolCodeCompletionItem>) -> List<PolySymbolCodeCompletionItem>)?,
  private val nameMatchFilter: ((PolySymbolQualifiedName, List<PolySymbol>) -> List<PolySymbol>)?,
) : PolySymbolScope {

  private val mapCache: ConcurrentMap<PolySymbolNamesProvider, BasicSearchMap> =
    ContainerUtil.createConcurrentSoftKeySoftValueMap()

  private fun provides(kind: PolySymbolKind): Boolean =
    kind in providesKinds || providesPredicate?.invoke(kind) == true

  override fun createPointer(): Pointer<BasicPolySymbolScopeWithMap> {
    val symbolPtrs = symbols.map { it.createPointer() }
    val providesKinds = providesKinds
    val providesPredicate = providesPredicate
    val codeCompletionFilter = codeCompletionFilter
    val nameMatchFilter = nameMatchFilter
    return Pointer {
      symbolPtrs.map { it.dereference() }.filterNotNull().takeIf { it.size == symbols.size }
        ?.let { BasicPolySymbolScopeWithMap(providesKinds, providesPredicate, it, codeCompletionFilter, nameMatchFilter) }
    }
  }

  override fun getMatchingSymbols(
    qualifiedName: PolySymbolQualifiedName,
    params: PolySymbolNameMatchQueryParams,
    stack: PolySymbolQueryStack,
  ): List<PolySymbol> {
    if (!provides(qualifiedName.kind)) return emptyList()
    val base = getMap(params.queryExecutor.namesProvider)
      .getMatchingSymbols(qualifiedName, params, stack.copy())
      .toList()
    val filter = nameMatchFilter ?: return base
    return filter(qualifiedName, base)
  }

  override fun getSymbols(
    kind: PolySymbolKind,
    params: PolySymbolListSymbolsQueryParams,
    stack: PolySymbolQueryStack,
  ): List<PolySymbol> =
    if (provides(kind))
      getMap(params.queryExecutor.namesProvider)
        .getSymbols(kind, params)
        .toList()
    else emptyList()

  override fun getCodeCompletions(
    qualifiedName: PolySymbolQualifiedName,
    params: PolySymbolCodeCompletionQueryParams,
    stack: PolySymbolQueryStack,
  ): List<PolySymbolCodeCompletionItem> {
    if (!provides(qualifiedName.kind)) return emptyList()
    val base = getMap(params.queryExecutor.namesProvider)
      .getCodeCompletions(qualifiedName, params, stack.copy())
      .toList()
    val filter = codeCompletionFilter ?: return base
    return filter(qualifiedName.kind, base)
  }

  private fun getMap(namesProvider: PolySymbolNamesProvider): BasicSearchMap =
    mapCache.getOrPut(namesProvider) {
      BasicSearchMap(namesProvider).also { map ->
        symbols.forEach { map.add(it) }
      }
    }

  private class BasicSearchMap(namesProvider: PolySymbolNamesProvider) : SearchMap<PolySymbol>(namesProvider) {
    override fun Sequence<PolySymbol>.mapAndFilter(params: PolySymbolQueryParams): Sequence<PolySymbol> = this

    fun add(symbol: PolySymbol) {
      add(symbol.qualifiedName, (symbol as? PolySymbolWithPattern)?.pattern, symbol)
    }
  }
}

internal class BasicPolySymbolScope(
  private val providesKinds: Set<PolySymbolKind>,
  private val providesPredicate: ((PolySymbolKind) -> Boolean)?,
  private val symbols: List<PolySymbol>,
  private val codeCompletionFilter: ((PolySymbolKind, List<PolySymbolCodeCompletionItem>) -> List<PolySymbolCodeCompletionItem>)?,
  private val nameMatchFilter: ((PolySymbolQualifiedName, List<PolySymbol>) -> List<PolySymbol>)?,
) : PolySymbolScope {

  private fun provides(kind: PolySymbolKind): Boolean =
    kind in providesKinds || providesPredicate?.invoke(kind) == true

  override fun createPointer(): Pointer<BasicPolySymbolScope> {
    val symbolPtrs = symbols.map { it.createPointer() }
    val providesKinds = providesKinds
    val providesPredicate = providesPredicate
    val codeCompletionFilter = codeCompletionFilter
    val nameMatchFilter = nameMatchFilter
    return Pointer {
      symbolPtrs.map { it.dereference() }.filterNotNull().takeIf { it.size == symbols.size }
        ?.let { BasicPolySymbolScope(providesKinds, providesPredicate, it, codeCompletionFilter, nameMatchFilter) }
    }
  }

  override fun getSymbols(
    kind: PolySymbolKind,
    params: PolySymbolListSymbolsQueryParams,
    stack: PolySymbolQueryStack,
  ): List<PolySymbol> =
    if (provides(kind))
      symbols.filter { it.kind == kind }
    else
      emptyList()

  override fun getMatchingSymbols(
    qualifiedName: PolySymbolQualifiedName,
    params: PolySymbolNameMatchQueryParams,
    stack: PolySymbolQueryStack,
  ): List<PolySymbol> {
    val base = super.getMatchingSymbols(qualifiedName, params, stack)
    val filter = nameMatchFilter ?: return base
    return filter(qualifiedName, base)
  }

  override fun getCodeCompletions(
    qualifiedName: PolySymbolQualifiedName,
    params: PolySymbolCodeCompletionQueryParams,
    stack: PolySymbolQueryStack,
  ): List<PolySymbolCodeCompletionItem> {
    val base = super.getCodeCompletions(qualifiedName, params, stack)
    val filter = codeCompletionFilter ?: return base
    return filter(qualifiedName.kind, base)
  }

}

internal class PolySymbolScopeBuilderImpl : PolySymbolScopeBuilder {

  private val providesKinds: MutableSet<PolySymbolKind> = mutableSetOf()
  private var providesPredicate: ((PolySymbolKind) -> Boolean)? = null
  private val symbols: MutableList<PolySymbol> = mutableListOf()
  private var codeCompletionFilter: ((PolySymbolKind, List<PolySymbolCodeCompletionItem>) -> List<PolySymbolCodeCompletionItem>)? = null
  private var nameMatchFilter: ((PolySymbolQualifiedName, List<PolySymbol>) -> List<PolySymbol>)? = null

  override fun provides(vararg kinds: PolySymbolKind) {
    providesKinds.addAll(kinds)
  }

  override fun provides(predicate: (PolySymbolKind) -> Boolean) {
    checkNoPsiCapture(predicate, "polySymbolScope.provides")
    providesPredicate = predicate
  }

  override fun filterCodeCompletions(
    filter: (kind: PolySymbolKind, items: List<PolySymbolCodeCompletionItem>) -> List<PolySymbolCodeCompletionItem>,
  ) {
    checkNoPsiCapture(filter, "polySymbolScope.filterCodeCompletions")
    codeCompletionFilter = filter
  }

  override fun filterNameMatches(
    filter: (name: PolySymbolQualifiedName, matches: List<PolySymbol>) -> List<PolySymbol>,
  ) {
    checkNoPsiCapture(filter, "polySymbolScope.filterNameMatches")
    nameMatchFilter = filter
  }

  override fun add(symbol: PolySymbol) {
    symbols += symbol
  }

  override fun addAll(symbols: Iterable<PolySymbol>) {
    this.symbols += symbols
  }

  override fun PolySymbol.unaryPlus() {
    symbols += this
  }

  override fun Iterable<PolySymbol>.unaryPlus() {
    symbols += this
  }

  override fun addSymbol(
    kind: PolySymbolKind,
    name: String,
    body: PolySymbolBuilder.() -> Unit,
  ) {
    symbols += polySymbol(kind, name, body)
  }

  fun build(): PolySymbolScope {
    val frozenKinds = providesKinds.toHashSet()
    val predicate = providesPredicate
    symbols.find { it.kind !in frozenKinds && predicate?.invoke(it.kind) != true }?.let {
      throw IllegalArgumentException("Symbol $it kind ${it.kind} is not provided by this scope. Use `provides` to specify supported symbol kinds.")
    }
    val frozenSymbols = symbols.toList()
    val codeCompletionFilter = codeCompletionFilter
    val nameMatchFilter = nameMatchFilter
    return if (frozenSymbols.size < 5)
      BasicPolySymbolScope(frozenKinds, predicate, frozenSymbols, codeCompletionFilter, nameMatchFilter)
    else
      BasicPolySymbolScopeWithMap(frozenKinds, predicate, frozenSymbols, codeCompletionFilter, nameMatchFilter)
  }
}

internal fun buildPolySymbolScope(
  configure: PolySymbolScopeBuilder.() -> Unit,
): PolySymbolScope {
  checkNoPsiCapture(configure, "polySymbolScope.configure")
  return PolySymbolScopeBuilderImpl().apply(configure).build()
}
