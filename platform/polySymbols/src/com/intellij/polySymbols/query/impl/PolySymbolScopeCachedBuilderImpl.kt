// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.query.impl

import com.intellij.model.Pointer
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.PolySymbolBuilder
import com.intellij.polySymbols.PolySymbolKind
import com.intellij.polySymbols.impl.checkNoPsiCapture
import com.intellij.polySymbols.polySymbol
import com.intellij.polySymbols.query.PolySymbolScopeCachedBuilder
import com.intellij.polySymbols.query.PolySymbolScopeCachedBuilderBase
import com.intellij.polySymbols.query.PolySymbolScopeInitializer
import com.intellij.polySymbols.query.PolySymbolScopeInitializerBase
import com.intellij.polySymbols.utils.PolySymbolScopeWithCache
import com.intellij.polySymbols.query.ProjectPolySymbolScopeCachedBuilder
import com.intellij.polySymbols.query.ProjectPolySymbolScopeInitializer
import com.intellij.polySymbols.query.PsiPolySymbolScopeCachedBuilder
import com.intellij.polySymbols.query.PsiPolySymbolScopeInitializer
import com.intellij.psi.PsiElement
import com.intellij.psi.createSmartPointer

internal abstract class AbstractBuilder<K>(
  override val project: Project,
  override val key: K,
) : PolySymbolScopeCachedBuilderBase<K> {

  var providesPredicate: (PolySymbolKind) -> Boolean = { false }
    private set

  var requiresResolveValue: Boolean = true
    private set

  final override fun provides(vararg kinds: PolySymbolKind) {
    val set = kinds.toHashSet()
    providesPredicate = { it in set }
  }

  final override fun provides(predicate: (PolySymbolKind) -> Boolean) {
    providesPredicate = predicate
  }

  final override fun requiresResolve(value: Boolean) {
    requiresResolveValue = value
  }
}

internal class ProjectPolySymbolScopeCachedBuilderImpl<K>(
  project: Project,
  key: K,
  private val configure: ProjectPolySymbolScopeCachedBuilder<K>.() -> Unit,
) : AbstractBuilder<K>(project, key), ProjectPolySymbolScopeCachedBuilder<K> {

  init {
    checkNoPsiCapture(configure, "polySymbolScopeCached.configure")
  }

  private var initBody: (ProjectPolySymbolScopeInitializer<K>.() -> Unit)? = null

  override fun initialize(body: ProjectPolySymbolScopeInitializer<K>.() -> Unit) {
    check(initBody == null) { "polySymbolScopeCached: initialize { } must be called exactly once." }
    checkNoPsiCapture(body, "polySymbolScopeCached.initialize")
    initBody = body
  }

  fun build(): BuiltPolySymbolScopeWithCache<Project, K> {
    configure(this)
    val body = initBody ?: error("polySymbolScopeCached: initialize { } was not called.")
    val projectRef = project
    val keyRef = key
    val configureRef = configure
    return BuiltPolySymbolScopeWithCache(
      project = projectRef,
      dataHolder = projectRef,
      scopeClass = configureRef::class.java,
      userKey = keyRef,
      providesPredicate = providesPredicate,
      requiresResolveValue = requiresResolveValue,
      pointerProvider = { Pointer.hardPointer(projectRef) },
      initializerFactory = { snapshotProject, _, snapshotKey, consumer, deps ->
        ProjectInitializerImpl(snapshotProject, snapshotKey, consumer, deps)
      },
      initBody = {
        @Suppress("UNCHECKED_CAST")
        body.invoke(this as ProjectPolySymbolScopeInitializer<K>)
      },
      reconstruct = { newProject ->
        ProjectPolySymbolScopeCachedBuilderImpl(newProject, keyRef, configureRef).build()
      },
    )
  }
}

internal class PsiPolySymbolScopeCachedBuilderImpl<T : PsiElement, K>(
  override val element: T,
  key: K,
  private val configure: PsiPolySymbolScopeCachedBuilder<T, K>.() -> Unit,
) : AbstractBuilder<K>(element.project, key), PsiPolySymbolScopeCachedBuilder<T, K> {

  init {
    checkNoPsiCapture(configure, "polySymbolScopeCached.configure")
  }

  private var initBody: (PsiPolySymbolScopeInitializer<T, K>.() -> Unit)? = null

  override fun initialize(body: PsiPolySymbolScopeInitializer<T, K>.() -> Unit) {
    check(initBody == null) { "polySymbolScopeCached: initialize { } must be called exactly once." }
    checkNoPsiCapture(body, "polySymbolScopeCached.initialize")
    initBody = body
  }

  fun build(): BuiltPolySymbolScopeWithCache<T, K> {
    configure(this)
    val body = initBody ?: error("polySymbolScopeCached: initialize { } was not called.")
    val keyRef = key
    val configureRef = configure
    return BuiltPolySymbolScopeWithCache(
      project = project,
      dataHolder = element,
      scopeClass = configureRef::class.java,
      userKey = keyRef,
      providesPredicate = providesPredicate,
      requiresResolveValue = requiresResolveValue,
      pointerProvider = { it.createSmartPointer() },
      initializerFactory = { snapshotProject, snapshotHolder, snapshotKey, consumer, deps ->
        PsiInitializerImpl(snapshotProject, snapshotHolder, snapshotKey, consumer, deps)
      },
      initBody = {
        @Suppress("UNCHECKED_CAST")
        body.invoke(this as PsiPolySymbolScopeInitializer<T, K>)
      },
      reconstruct = { newElement ->
        PsiPolySymbolScopeCachedBuilderImpl(newElement, keyRef, configureRef).build()
      },
    )
  }
}

internal class UserDataHolderPolySymbolScopeCachedBuilderImpl<T : UserDataHolder, K>(
  project: Project,
  override val dataHolder: T,
  key: K,
  private val configure: PolySymbolScopeCachedBuilder<T, K>.() -> Unit,
) : AbstractBuilder<K>(project, key), PolySymbolScopeCachedBuilder<T, K> {

  init {
    checkNoPsiCapture(configure, "polySymbolScopeCached.configure")
  }

  private var initBody: (PolySymbolScopeInitializer<T, K>.() -> Unit)? = null
  private var pointerProvider: ((T) -> Pointer<out T>)? = null

  override fun pointer(provider: (T) -> Pointer<out T>) {
    check(pointerProvider == null) { "polySymbolScopeCached: pointer { } must be called exactly once." }
    checkNoPsiCapture(provider, "polySymbolScopeCached.pointer")
    pointerProvider = provider
  }

  override fun initialize(body: PolySymbolScopeInitializer<T, K>.() -> Unit) {
    check(initBody == null) { "polySymbolScopeCached: initialize { } must be called exactly once." }
    checkNoPsiCapture(body, "polySymbolScopeCached.initialize")
    initBody = body
  }

  fun build(): BuiltPolySymbolScopeWithCache<T, K> {
    configure(this)
    val body = initBody ?: error("polySymbolScopeCached: initialize { } was not called.")
    val pointer = pointerProvider
                  ?: error("polySymbolScopeCached: pointer { } is required for non-PsiElement/non-Project holders.")
    val projectRef = project
    val keyRef = key
    val configureRef = configure
    return BuiltPolySymbolScopeWithCache(
      project = projectRef,
      dataHolder = dataHolder,
      scopeClass = configureRef::class.java,
      userKey = keyRef,
      providesPredicate = providesPredicate,
      requiresResolveValue = requiresResolveValue,
      pointerProvider = pointer,
      initializerFactory = { snapshotProject, snapshotHolder, snapshotKey, consumer, deps ->
        UserDataHolderInitializerImpl(snapshotProject, snapshotHolder, snapshotKey, consumer, deps)
      },
      initBody = {
        @Suppress("UNCHECKED_CAST")
        body.invoke(this as PolySymbolScopeInitializer<T, K>)
      },
      reconstruct = { newHolder ->
        UserDataHolderPolySymbolScopeCachedBuilderImpl(projectRef, newHolder, keyRef, configureRef).build()
      },
    )
  }
}

// ─── Initializer impls ────────────────────────────────────────────────────────

private abstract class AbstractInitializer<K>(
  override val project: Project,
  override val key: K,
  private val consumer: (PolySymbol) -> Unit,
  private val cacheDeps: MutableSet<Any>,
) : PolySymbolScopeInitializerBase<K> {

  final override fun cacheDependencies(vararg dependencies: Any) {
    for (dep in dependencies) cacheDeps.add(dep)
  }

  final override fun add(symbol: PolySymbol) {
    consumer(symbol)
  }

  final override fun addAll(symbols: Iterable<PolySymbol>) {
    symbols.forEach(consumer)
  }

  final override fun PolySymbol.unaryPlus() {
    consumer(this)
  }

  final override fun Iterable<PolySymbol>.unaryPlus() {
    forEach(consumer)
  }

  final override fun addSymbol(
    kind: PolySymbolKind,
    name: String,
    body: PolySymbolBuilder.() -> Unit,
  ) {
    consumer(polySymbol(kind, name, body))
  }
}

private class ProjectInitializerImpl<K>(
  project: Project,
  key: K,
  consumer: (PolySymbol) -> Unit,
  cacheDeps: MutableSet<Any>,
) : AbstractInitializer<K>(project, key, consumer, cacheDeps),
    ProjectPolySymbolScopeInitializer<K>

private class PsiInitializerImpl<T : PsiElement, K>(
  project: Project,
  override val element: T,
  key: K,
  consumer: (PolySymbol) -> Unit,
  cacheDeps: MutableSet<Any>,
) : AbstractInitializer<K>(project, key, consumer, cacheDeps),
    PsiPolySymbolScopeInitializer<T, K>

private class UserDataHolderInitializerImpl<T : UserDataHolder, K>(
  project: Project,
  override val dataHolder: T,
  key: K,
  consumer: (PolySymbol) -> Unit,
  cacheDeps: MutableSet<Any>,
) : AbstractInitializer<K>(project, key, consumer, cacheDeps),
    PolySymbolScopeInitializer<T, K>

// ─── Built scope ──────────────────────────────────────────────────────────────

internal class BuiltPolySymbolScopeWithCache<T : UserDataHolder, K>(
  project: Project,
  dataHolder: T,
  scopeClass: Class<*>,
  private val userKey: K,
  private val providesPredicate: (PolySymbolKind) -> Boolean,
  private val requiresResolveValue: Boolean,
  private val pointerProvider: (T) -> Pointer<out T>,
  private val initializerFactory: (
    Project,
    T,
    K,
    (PolySymbol) -> Unit,
    MutableSet<Any>,
  ) -> PolySymbolScopeInitializerBase<K>,
  private val initBody: PolySymbolScopeInitializerBase<K>.() -> Unit,
  private val reconstruct: (T) -> BuiltPolySymbolScopeWithCache<T, K>,
) : PolySymbolScopeWithCache<T, Pair<Class<*>, K>>(project, dataHolder, scopeClass to userKey) {

  override fun provides(kind: PolySymbolKind): Boolean =
    providesPredicate(kind)

  override val requiresResolve: Boolean
    get() = requiresResolveValue

  override fun initialize(consumer: (PolySymbol) -> Unit, cacheDependencies: MutableSet<Any>) {
    val initializer = initializerFactory(project, dataHolder, userKey, consumer, cacheDependencies)
    initBody.invoke(initializer)
  }

  override fun createPointer(): Pointer<out BuiltPolySymbolScopeWithCache<T, K>> {
    val dataPointer = pointerProvider(dataHolder)
    val reconstruct = this.reconstruct
    return Pointer {
      dataPointer.dereference()?.let { reconstruct(it) }
    }
  }
}
