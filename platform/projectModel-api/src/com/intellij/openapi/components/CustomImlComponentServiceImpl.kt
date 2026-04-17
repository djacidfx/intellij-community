// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.components

import com.intellij.configurationStore.deserialize
import com.intellij.configurationStore.jdomSerializer
import com.intellij.configurationStore.serializeObjectInto
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.JDOMUtil
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.jps.entities.CustomImlComponentEntity
import com.intellij.platform.workspace.jps.entities.CustomImlComponentEntityBuilder
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.ModuleEntityBuilder
import com.intellij.platform.workspace.jps.entities.customImlComponent
import com.intellij.util.xmlb.Constants
import org.jdom.Element

private const val COMPONENT_ELEMENT: String = "component"

internal class CustomImlComponentServiceImpl(
  private val project: Project,
) : CustomImlComponentService {

  override fun <T> getComponentValue(module: ModuleEntity, componentName: String, componentClass: Class<T>): T? {
    val entity = module.customImlComponent ?: return null
    val component = entity.components[componentName] ?: return null
    return JDOMUtil.load(component).deserialize(componentClass)
  }

  override suspend fun <T> setComponentValue(module: ModuleEntity, componentName: String, component: T) {
    val componentTag = Element(COMPONENT_ELEMENT)
    componentTag.setAttribute(Constants.NAME, componentName)
    serializeObjectInto(component as Any, componentTag, jdomSerializer.getDefaultSerializationFilter())
    val rawContent = JDOMUtil.write(componentTag)
    val entity = module.customImlComponent
    val newComponent = mapOf(componentName to rawContent)


    project.workspaceModel.update("Update component: $componentName") { storage ->
      if (entity != null) {
        storage.modifyEntity(CustomImlComponentEntityBuilder::class.java, entity) {
          components += newComponent
        }
      }
      else {
        val newEntity = CustomImlComponentEntity(newComponent, module.entitySource)
        storage.modifyEntity(ModuleEntityBuilder::class.java, module) {
          customImlComponent = newEntity
        }
      }
    }
  }
}
