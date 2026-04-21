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
import kotlin.reflect.safeCast

@DslMarker
@Target(AnnotationTarget.CLASS)
annotation class PolySymbolDsl

/**
 * Opaque handle returned by [PolySymbolDslBuilderBase.dependency] calls inside a PolySymbol DSL
 * builder block. Accessing a handle outside a builder method throws [IllegalStateException].
 *
 * You may use it with `by` syntax, get value property directly, or invoke it to get the value.
 *
 * Examples:
 * ```kotlin
 * val element: JSVariable // JSVariable is a PsiElement
 *
 * // using `by` syntax
 * polySymbol(JS_SYMBOLS, "variable") {
 *   val element by dependency(element)
 *   property(JSTypeProperty) {
 *     element.jsType
 *   }
 * }
 *
 * // using invoke on handle
 * polySymbol(JS_SYMBOLS, "variable") {
 *   val element = dependency(element)
 *   property(JSTypeProperty) {
 *     element().jsType
 *   }
 * }
 *
 * // using value property on handle
 * polySymbol(JS_SYMBOLS, "variable") {
 *   val element = dependency(element)
 *   property(JSTypeProperty) {
 *     element.value.jsType
 *   }
 * }
 * ```
 */
@PolySymbolDsl
class DependencyHandle<T : Any> internal constructor(internal val index: Int) {

  operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
    val scope = currentDependencyScope.get()
                ?: error("DependencyHandle `${property.name}` accessed outside a PolySymbol DSL getter lambda")
    @Suppress("UNCHECKED_CAST")
    return scope.resolved[index] as T
  }

  val value: T
    get() = getValue(null, VALUE_READ_PROP)

  operator fun invoke(): T =
    getValue(null, VALUE_READ_PROP)
}

private val currentDependencyScope: ThreadLocal<DependencyScope> = ThreadLocal()

internal val PSI_CONTEXT_READ_PROP: KProperty<*> = ::PSI_CONTEXT_READ_PROP
internal val VALUE_READ_PROP: KProperty<*> = ::VALUE_READ_PROP

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
internal class DependencyScope internal constructor(internal val resolved: List<Any>) {

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
 * [PolySymbol.createPointer] is invoked.
 */
@PolySymbolDsl
interface PolySymbolDslBuilderBase {

  /**
   * @see [PolySymbol.priority]
   */
  fun priority(value: PolySymbol.Priority?)

  /**
   * @see [PolySymbol.priority]
   */
  fun priority(provider: () -> PolySymbol.Priority?)

  /**
   * @see [PolySymbol.apiStatus]
   */
  fun apiStatus(value: PolySymbolApiStatus)

  /**
   * @see [PolySymbol.apiStatus]
   */
  fun apiStatus(provider: () -> PolySymbolApiStatus)

  /**
   * @see [PolySymbol.modifiers]
   */
  fun modifiers(value: Set<PolySymbolModifier>)

  /**
   * @see [PolySymbol.modifiers]
   */
  fun modifiers(provider: () -> Set<PolySymbolModifier>)

  /**
   * @see [PolySymbol.icon]
   */
  fun icon(value: Icon?)

  /**
   * @see [PolySymbol.icon]
   */
  fun icon(provider: () -> Icon?)

  /**
   * @see [PolySymbol.get]
   */
  fun <T : Any> property(property: PolySymbolProperty<T>, value: T?)

  /**
   * @see [PolySymbol.get]
   */
  fun <T : Any> property(property: PolySymbolProperty<T>, provider: () -> T?)

  /**
   * Declare a [PsiElement] dependency.
   *
   * Using this method allows for automatic management of pointers.
   *
   * Example usage:
   * ```kotlin
   *
   * val variable: JSVariable // JSVariable is a PsiElement
   *
   * polySymbol(JS_SYMBOLS, "variable") {
   *   val variable by dependency(variable)
   *   property(JSTypeProperty) {
   *     variable.jsType
   *   }
   * }
   * ```
   * @see [DependencyHandle]
   */
  fun <T : PsiElement> dependency(element: T): DependencyHandle<T>

  /**
   * Declare a generic dependency by providing the current value and a pointer provider.
   *
   * Using this method allows for automatic management of pointers.
   *
   * @see [DependencyHandle]
   */
  fun <T : Any> dependency(`object`: T, pointerProvider: (T) -> Pointer<out T>): DependencyHandle<T>
}

/**
 * Declare a [PolySymbol] dependency. The [Pointer] created from the provided [symbol]
 * must dereference to the [T] class. If that's not
 * true, use the overload with the custom pointer provider.
 *
 * Using this method allows for automatic management of pointers.
 *
 * Example usage:
 * ```kotlin
 * val source: PolySymbol
 *
 * polySymbol(JS_SYMBOLS, "variable") {
 *   val source by dependency(source)
 *   property(JSTypeProperty) {
 *     source[JSTypeProperty]
 *   }
 * }
 * ```
 * @see [DependencyHandle]
 */
inline fun <reified T : PolySymbol> PolySymbolDslBuilderBase.dependency(symbol: T): DependencyHandle<T> =
  dependency(symbol) {
    val symbolPointer = it.createPointer()
    val symbolClass = T::class
    Pointer {
      symbolClass.safeCast(symbolPointer.dereference())
    }
  }

internal sealed interface DepSpec<T : Any> {
  fun currentValue(): T
  fun toPointer(): Pointer<out T>

  class FromPsiElement<T : PsiElement>(val element: T) : DepSpec<T> {
    override fun currentValue(): T = element
    override fun toPointer(): Pointer<out T> {
      return element.createSmartPointer()
    }
  }

  class FromGenericObject<T : Any>(val `object`: T, val pointerProvider: (T) -> Pointer<out T>) : DepSpec<T> {
    override fun currentValue(): T = `object`
    override fun toPointer(): Pointer<out T> = pointerProvider(`object`)
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

  class FromSpecs(val specs: List<DepSpec<*>>) : DependencySource {
    override val isEmpty: Boolean get() = specs.isEmpty()
    override fun snapshot(): List<Any> {
      val values = ArrayList<Any>(specs.size)
      for (spec in specs) values += spec.currentValue()
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
