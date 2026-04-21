// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.polySymbols.dsl

import com.intellij.model.Pointer
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.PolySymbolApiStatus
import com.intellij.polySymbols.PolySymbolModifier
import com.intellij.polySymbols.PolySymbolProperty
import com.intellij.psi.PsiElement
import org.jetbrains.annotations.ApiStatus
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
@ApiStatus.NonExtendable
interface DependencyHandle<T : Any> {

  operator fun getValue(thisRef: Any?, property: KProperty<*>): T

  val value: T

  operator fun invoke(): T
}

/**
 * Base class shared by every PolySymbol-building DSL. Collects raw
 * dependency references (PsiElement / PolySymbol) without eagerly creating
 * pointers — pointers are only allocated when the materialized symbol's
 * [PolySymbol.createPointer] is invoked.
 */
@PolySymbolDsl
@ApiStatus.NonExtendable
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
