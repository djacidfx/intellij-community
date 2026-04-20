// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.dsl

import com.intellij.model.Pointer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.PolySymbolApiStatus
import com.intellij.polySymbols.PolySymbolModifier
import com.intellij.polySymbols.PolySymbolProperty
import com.intellij.psi.PsiElement
import com.intellij.psi.createSmartPointer
import javax.swing.Icon
import kotlin.reflect.KProperty

@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class PolySymbolDsl

/**
 * Opaque handle returned by `dependency(...)` calls inside a PolySymbol DSL
 * builder block. Used as a delegated-property value — inside any
 * getter lambda run through [DependencyScope.invoke] it reads the
 * pre-resolved, non-null value from the enclosing symbol's snapshot.
 *
 * Accessing a handle outside a DependencyScope lambda throws
 * `IllegalStateException`.
 */
@PolySymbolDsl
class DependencyHandle<T : Any> internal constructor(internal val index: Int) {

  operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
    val scope = currentDependencyScope.get()
      ?: error("DependencyHandle `${property.name}` accessed outside a PolySymbol DSL getter lambda")
    @Suppress("UNCHECKED_CAST")
    return scope.resolved[index] as T
  }
}

private val currentDependencyScope: ThreadLocal<DependencyScope?> = ThreadLocal()

internal val PSI_CONTEXT_READ_PROP: KProperty<*> = ::PSI_CONTEXT_READ_PROP

/**
 * Read the current value of a [DependencyHandle] — assuming the caller has
 * ensured an ambient [DependencyScope] is active. Equivalent to reading a
 * `by dependency(...)` delegated property.
 */
internal fun <T : Any> DependencyHandle<T>.readInScope(): T =
  getValue(null, PSI_CONTEXT_READ_PROP)

/**
 * Receiver of every DSL getter lambda. Carries the pre-resolved values for
 * all declared dependencies of the currently-materialized symbol.
 */
@PolySymbolDsl
class DependencyScope internal constructor(internal val resolved: List<Any>) {

  /**
   * Runs [block] with this scope set as the ambient dependency scope,
   * restoring the previous value on exit. Reading any `by dependency(...)`
   * delegate inside [block] returns the pre-resolved value.
   */
  internal inline fun <R> withinScope(block: DependencyScope.() -> R): R {
    val prev = currentDependencyScope.get()
    currentDependencyScope.set(this)
    try {
      return this.block()
    }
    finally {
      currentDependencyScope.set(prev)
    }
  }

  /**
   * Runs [block] with this scope set as ambient, passing [arg] to the block.
   * Used for multi-arg getter lambdas (e.g. `documentationTarget(location) { … }`).
   */
  internal inline fun <A, R> withinScope(arg: A, block: DependencyScope.(A) -> R): R {
    val prev = currentDependencyScope.get()
    currentDependencyScope.set(this)
    try {
      return this.block(arg)
    }
    finally {
      currentDependencyScope.set(prev)
    }
  }
}

/**
 * Base class shared by every PolySymbol-building DSL. Collects raw
 * dependency references (PsiElement / PolySymbol) without eagerly creating
 * pointers — pointers are only allocated when the materialized symbol's
 * `createPointer()` is invoked.
 */
@PolySymbolDsl
abstract class PolySymbolDslBuilderBase internal constructor() {

  internal abstract val builderContext: String

  internal val depSpecs: MutableList<DepSpec> = mutableListOf()

  // === Common overridable properties ===
  //
  // Each is stored as an optional zero-arg lambda; the direct-value setter
  // captures the value in a constant lambda so the read side has a uniform
  // shape. A `null` getter means "no override" — in which case the produced
  // symbol falls back to the interface default.

  internal var priorityValue: PolySymbol.Priority? = null
  internal var priorityGetter: (() -> PolySymbol.Priority?)? = null

  internal var apiStatusValue: PolySymbolApiStatus? = null
  internal var apiStatusGetter: (() -> PolySymbolApiStatus)? = null

  internal var modifiersValue: Set<PolySymbolModifier>? = null
  internal var modifiersGetter: (() -> Set<PolySymbolModifier>)? = null

  internal var iconValue: Icon? = null
  internal var iconGetter: (() -> Icon?)? = null

  internal val propertyValues: MutableMap<PolySymbolProperty<*>, Any?> = mutableMapOf()
  internal val propertyGetters: MutableMap<PolySymbolProperty<*>, () -> Any?> = mutableMapOf()

  fun priority(value: PolySymbol.Priority?) {
    priorityValue = value
    priorityGetter = null
  }

  fun priority(provider: () -> PolySymbol.Priority?) {
    checkNoPsiCapture(provider, "buildPolySymbol.priority")
    priorityGetter = provider
  }

  fun apiStatus(value: PolySymbolApiStatus) {
    apiStatusValue = value
    apiStatusGetter = null
  }

  fun apiStatus(provider: () -> PolySymbolApiStatus) {
    checkNoPsiCapture(provider, "buildPolySymbol.apiStatus")
    apiStatusGetter = provider
  }

  fun modifiers(value: Set<PolySymbolModifier>) {
    modifiersValue = value
    modifiersGetter = null
  }

  fun modifiers(provider: () -> Set<PolySymbolModifier>) {
    checkNoPsiCapture(provider, "buildPolySymbol.modifiers")
    modifiersGetter = provider
  }

  fun icon(value: Icon?) {
    iconValue = value
    iconGetter = null
  }

  fun icon(provider: () -> Icon?) {
    checkNoPsiCapture(provider, "buildPolySymbol.icon")
    iconGetter = provider
  }

  fun <T : Any> property(property: PolySymbolProperty<T>, value: T?) {
    propertyValues[property] = value
    propertyGetters -= property
  }

  fun <T : Any> property(property: PolySymbolProperty<T>, provider: () -> T?) {
    checkNoPsiCapture(provider, "buildPolySymbol.property[${property.name}]")
    propertyValues -= property
    propertyGetters[property] = provider
  }


  /**
   * Declare a [PsiElement] dependency. The element is retained as a raw
   * reference; when the enclosing symbol is later converted to a pointer, a
   * [com.intellij.psi.SmartPsiElementPointer] is created at that moment.
   */
  fun dependency(element: PsiElement): DependencyHandle<PsiElement> {
    val idx = depSpecs.size
    depSpecs += DepSpec.FromPsiElement(element)
    return DependencyHandle(idx)
  }

  /**
   * Declare a [PolySymbol] dependency. Captured analogously — the symbol's
   * own [PolySymbol.createPointer] is only invoked when the enclosing
   * symbol's pointer is created.
   */
  fun <T : PolySymbol> dependency(symbol: T): DependencyHandle<T> {
    val idx = depSpecs.size
    depSpecs += DepSpec.FromPolySymbol(symbol)
    return DependencyHandle(idx)
  }

  /**
   * Snapshot the current values of all declared dependencies. Returns
   * `null` if any dep cannot be resolved (e.g. a previously captured
   * PolySymbol has become invalid).
   */
  internal fun resolveSnapshot(): List<Any>? {
    if (depSpecs.isEmpty()) return emptyList()
    val values = ArrayList<Any>(depSpecs.size)
    for (spec in depSpecs) {
      values += spec.currentValue() ?: run {
        LOG.error("Failed to dereference dependency ($spec) within its initial ReadAction.")
        return null
      }
    }
    return values
  }
}

/** Raw dep capture — stores the original reference, not a pointer. */
internal sealed interface DepSpec {
  /** Returns the current value of the dep, or `null` if it is no longer valid. */
  fun currentValue(): Any?

  /** Creates a pointer for later cross-read-action survival. Called lazily. */
  fun toPointer(): Pointer<out Any>

  class FromPsiElement(val element: PsiElement) : DepSpec {
    override fun currentValue(): Any? = element.takeIf { it.isValid }
    override fun toPointer(): Pointer<out Any> {
      val smart = element.createSmartPointer()
      return Pointer { smart.element }
    }
  }

  class FromPolySymbol(val symbol: PolySymbol) : DepSpec {
    override fun currentValue(): Any = symbol
    override fun toPointer(): Pointer<out Any> = symbol.createPointer()
  }
}

/**
 * Carrier of a materialized symbol's dependency roots. Flips from
 * [FromSpecs] (initial instance, still in its creation read action) to
 * [FromPointers] the first time a pointer is needed, and subsequent
 * cross-read-action instances carry [FromPointers] onward.
 */
internal sealed interface DependencySource {
  val isEmpty: Boolean

  /** Snapshot the current values of all deps. Null on failure. */
  fun snapshot(): List<Any>?

  /** Pointer list for long-term survival — materialized lazily by [FromSpecs]. */
  fun pointers(): List<Pointer<out Any>>

  class FromSpecs(val specs: List<DepSpec>) : DependencySource {
    override val isEmpty: Boolean get() = specs.isEmpty()
    override fun snapshot(): List<Any>? {
      val values = ArrayList<Any>(specs.size)
      for (spec in specs) values += spec.currentValue() ?: return null
      return values
    }

    private val lazyPointers: List<Pointer<out Any>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
      specs.map { it.toPointer() }
    }
    override fun pointers(): List<Pointer<out Any>> = lazyPointers
  }

  class FromPointers(private val pointers: List<Pointer<out Any>>) : DependencySource {
    override val isEmpty: Boolean get() = pointers.isEmpty()
    override fun snapshot(): List<Any>? {
      val values = ArrayList<Any>(pointers.size)
      for (pointer in pointers) values += pointer.dereference() ?: return null
      return values
    }
    override fun pointers(): List<Pointer<out Any>> = pointers
  }
}

private val PSI_OR_POLY_SYMBOL_TYPES = arrayOf(
  PsiElement::class.java,
  PolySymbol::class.java,
)

internal val LOG = logger<DependencyHandle<*>>()

/**
 * In dev/test builds, reflect on [lambda] and fail fast if any captured
 * field holds a [PsiElement] or [PolySymbol] directly — those are only
 * valid within one read action, so they must be wrapped in `dependency(…)`
 * to survive.
 */
internal fun checkNoPsiCapture(lambda: Any, context: String) {
  val app = ApplicationManager.getApplication() ?: return
  if (!app.isUnitTestMode && !app.isInternal && !app.isEAP) return
  for (field in lambda::class.java.declaredFields) {
    val fieldType = field.type
    for (forbidden in PSI_OR_POLY_SYMBOL_TYPES) {
      if (forbidden.isAssignableFrom(fieldType)) {
        LOG.error(
          "$context lambda captures a ${forbidden.simpleName} " +
          "(${fieldType.name} as field ${field.name}). Declare it with dependency(...) " +
          "so it survives read-action boundaries."
        )
      }
    }
  }
}
