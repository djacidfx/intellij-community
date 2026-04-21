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
  private val providesPredicate: (PolySymbolKind) -> Boolean,
  private val symbols: List<PolySymbol>,
) : PolySymbolScope {

  private val mapCache: ConcurrentMap<PolySymbolNamesProvider, BasicSearchMap> =
    ContainerUtil.createConcurrentSoftKeySoftValueMap()

  override fun createPointer(): Pointer<BasicPolySymbolScopeWithMap> {
    val symbolPtrs = symbols.map { it.createPointer() }
    val providesPredicate = providesPredicate
    return Pointer {
      symbolPtrs.map { it.dereference() }.filterNotNull().takeIf { it.size == symbols.size }
        ?.let { BasicPolySymbolScopeWithMap(providesPredicate, it) }
    }
  }

  override fun getMatchingSymbols(
    qualifiedName: PolySymbolQualifiedName,
    params: PolySymbolNameMatchQueryParams,
    stack: PolySymbolQueryStack,
  ): List<PolySymbol> =
    if (providesPredicate(qualifiedName.kind))
      getMap(params.queryExecutor.namesProvider)
        .getMatchingSymbols(qualifiedName, params, stack.copy())
        .toList()
    else
      emptyList()

  override fun getSymbols(
    kind: PolySymbolKind,
    params: PolySymbolListSymbolsQueryParams,
    stack: PolySymbolQueryStack,
  ): List<PolySymbol> =
    if (providesPredicate(kind))
      getMap(params.queryExecutor.namesProvider)
        .getSymbols(kind, params)
        .toList()
    else emptyList()

  override fun getCodeCompletions(
    qualifiedName: PolySymbolQualifiedName,
    params: PolySymbolCodeCompletionQueryParams,
    stack: PolySymbolQueryStack,
  ): List<PolySymbolCodeCompletionItem> =
    if (providesPredicate(qualifiedName.kind))
      getMap(params.queryExecutor.namesProvider)
        .getCodeCompletions(qualifiedName, params, stack.copy())
        .toList()
    else emptyList()

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
  private val providesPredicate: (PolySymbolKind) -> Boolean,
  private val symbols: List<PolySymbol>,
) : PolySymbolScope {

  override fun createPointer(): Pointer<BasicPolySymbolScope> {
    val symbolPtrs = symbols.map { it.createPointer() }
    val providesPredicate = providesPredicate
    return Pointer {
      symbolPtrs.map { it.dereference() }.filterNotNull().takeIf { it.size == symbols.size }
        ?.let { BasicPolySymbolScope(providesPredicate, it) }
    }
  }

  override fun getSymbols(
    kind: PolySymbolKind,
    params: PolySymbolListSymbolsQueryParams,
    stack: PolySymbolQueryStack,
  ): List<PolySymbol> =
    if (providesPredicate(kind))
      symbols.filter { it.kind == kind }
    else
      emptyList()

}

internal class PolySymbolScopeBuilderImpl : PolySymbolScopeBuilder {

  private var providesPredicate: (PolySymbolKind) -> Boolean = { false }
  private val symbols: MutableList<PolySymbol> = mutableListOf()

  override fun provides(vararg kinds: PolySymbolKind) {
    val set = kinds.toHashSet()
    providesPredicate = { it in set }
  }

  override fun provides(predicate: (PolySymbolKind) -> Boolean) {
    providesPredicate = predicate
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
    symbols.find { !providesPredicate(it.kind) }?.let {
      throw IllegalArgumentException("Symbol $it kind ${it.kind} is not provided by this scope. Use `provides` to specify supported symbol kinds.")
    }
    if (symbols.size < 5)
      return BasicPolySymbolScope(providesPredicate, symbols.toList())
    else
      return BasicPolySymbolScopeWithMap(providesPredicate, symbols.toList())
  }
}

internal fun buildPolySymbolScope(
  configure: PolySymbolScopeBuilder.() -> Unit,
): PolySymbolScope {
  checkNoPsiCapture(configure, "polySymbolScope.configure")
  return PolySymbolScopeBuilderImpl().apply(configure).build()
}
