// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.options

import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId
import org.jetbrains.annotations.ApiStatus
import java.io.File
import java.util.function.Predicate

@ApiStatus.NonExtendable
abstract class SchemeManager<T> {
  abstract val allSchemes: List<T>

  open val isEmpty: Boolean
    get() = allSchemes.isEmpty()

  abstract val activeScheme: T?

  /**
   * If schemes are lazily loaded, you can use this method to delay scheme selection.
   * The scheme will then be located by its name upon the first use.
   */
  abstract var currentSchemeName: String?

  abstract val allSchemeNames: Collection<String>

  abstract val rootDirectory: File

  abstract fun loadSchemes(): Collection<T>

  abstract fun reload()

  @Deprecated(message = "Use reload()", replaceWith = ReplaceWith("reload()"))
  open fun reload(retainFilter: ((scheme: T) -> Boolean)?) {
    reload()
  }

  fun addScheme(scheme: T) {
    addScheme(scheme, replaceExisting = true)
  }

  abstract fun addScheme(scheme: T, replaceExisting: Boolean)

  abstract fun findSchemeByName(schemeName: String): T?

  abstract fun setCurrentSchemeName(schemeName: String?, notify: Boolean)

  @JvmOverloads
  open fun setCurrent(scheme: T?, notify: Boolean = true, processChangeSynchronously: Boolean = false) { }

  abstract fun removeScheme(scheme: T): Boolean

  abstract fun removeScheme(name: String): T?

  /**
   * Must be called before [loadSchemes].
   * Scheme manager processor must be [com.intellij.configurationStore.LazySchemeProcessor].
   */
  @ApiStatus.Internal
  abstract fun loadBundledScheme(resourceName: String, requestor: Any?, pluginDescriptor: PluginDescriptor?): T?

  @ApiStatus.Internal
  interface LoadBundleSchemeRequest<T> {
    val pluginId: PluginId
    val schemeKey: String
    fun loadBytes(): ByteArray
    fun createScheme(): T
  }

  @ApiStatus.Internal
  abstract fun loadBundledSchemes(providers: Sequence<LoadBundleSchemeRequest<T>>)

  @JvmOverloads
  open fun setSchemes(newSchemes: List<T>, newCurrentScheme: T? = null, removeCondition: Predicate<T>? = null) { }

  /**
   * Bundled / read-only (or overriding) scheme cannot be renamed or deleted.
   */
  abstract fun isMetadataEditable(scheme: T): Boolean

  abstract fun save()

  /**
   * Returns the category which settings of this scheme belong to.
   */
  abstract fun getSettingsCategory(): SettingsCategory
}
