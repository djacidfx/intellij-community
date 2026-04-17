// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.components

import com.intellij.openapi.project.Project
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import org.jetbrains.annotations.ApiStatus

/**
 * Provides access to module-level services persistent components.
 *
 * This API allows migrating module-level services which implements `PersistentStateComponent`
 * to project-level services, while storing data in `*.iml` file the same way it was stored before.
 *
 * Suppose you have the following module-level service.
 *
 * ```kotlin
 * @State(name = "MyModuleServiceState")
 * class MyModuleService : PersistentStateComponent<MyState> {
 *   var state: MyState = MyState()
 *
 *   override fun getState(): MyState {
 *     return state
 *   }
 *   override fun loadState(value: MyState) {
 *     state = value
 *   }
 * }
 * ```
 * After migrating it to this API it would look like this.
 *
 * ```kotlin
 * const val COMPONENT_NAME = "MyModuleServiceState"
 * class MyModuleService(private val module: Module) {

 *   val componentService = CustomImlComponentService.getInstance(module.project)
 *
 *   fun getState(): MyState? {
 *     return componentService.getComponentValue<MyState>(module.findModuleEntity()!!, COMPONENT_NAME)
 *   }
 *
 *   suspend fun setState(value: MyState) {
 *     componentService.setComponentValue(module.findModuleEntity()!!, COMPONENT_NAME, value)
 *   }
 * }
 * ```
 */
@ApiStatus.Experimental
interface CustomImlComponentService {
  /**
   *  Returns the deserialized value of a persistent component, or `null` if the component is not stored for the module.
   */
  fun <T> getComponentValue(module: ModuleEntity, componentName: String, componentClass: Class<T>): T?

  /**
   * Serializes and stores a persistent component value for the given module.
   */
  suspend fun <T> setComponentValue(module: ModuleEntity, componentName: String, component: T)

  companion object {
    fun getInstance(project: Project): CustomImlComponentService {
      return project.service<CustomImlComponentService>()
    }
  }
}

/** Convenience overload for [getComponentValue]. */
inline fun <reified T> CustomImlComponentService.getComponentValue(module: ModuleEntity, componentName: String): T? {
  return getComponentValue(module, componentName, T::class.java)
}