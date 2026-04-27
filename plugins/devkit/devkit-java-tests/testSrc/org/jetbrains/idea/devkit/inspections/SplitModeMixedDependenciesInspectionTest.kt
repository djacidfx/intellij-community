// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections

import com.intellij.openapi.project.IntelliJProjectUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.common.waitUntil
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import org.jetbrains.idea.devkit.build.PluginBuildConfiguration
import org.jetbrains.idea.devkit.inspections.remotedev.SplitModeApiRestrictionsService
import org.jetbrains.idea.devkit.inspections.remotedev.SplitModeMixedDependenciesInspection
import org.jetbrains.idea.devkit.module.PluginModuleType
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.junit.Assert
import kotlin.time.Duration.Companion.seconds

internal class SplitModeMixedDependenciesInspectionTest : JavaCodeInsightFixtureTestCase() {
  override fun setUp() {
    super.setUp()
    IntelliJProjectUtil.markAsIntelliJPlatformProject(project, true)

    val service = SplitModeApiRestrictionsService.getInstance()
    service.scheduleLoadRestrictions()
    timeoutRunBlocking {
      waitUntil("API restrictions failed to load", 2.seconds) { service.isLoaded() }
    }

    myFixture.enableInspections(SplitModeMixedDependenciesInspection())
  }

  fun testMixedModuleDependenciesInPluginXml() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.17",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      pluginXmlContent = """
        <<error descr="This module effectively depends on frontend-only and backend-only modules simultaneously. It will not get loaded in runtime. Reason: frontend dependencies: dependency 'intellij.platform.frontend' from descriptor 'plugin.xml' in module 'unique.module.name.17'; backend dependencies: dependency 'intellij.platform.backend' from descriptor 'plugin.xml' in module 'unique.module.name.17'">idea-plugin</error>>
          <dependencies>
            <module name="intellij.platform.frontend"/>
            <module name="intellij.platform.rpc.split"/>
            <module name="intellij.platform.backend"/>
          </dependencies>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testMixedPluginDependenciesInPluginXml() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.18",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      pluginXmlContent = """
        <<error descr="This module effectively depends on frontend-only and backend-only modules simultaneously. It will not get loaded in runtime. Reason: frontend dependencies: dependency 'com.intellij.jetbrains.client' from descriptor 'plugin.xml' in module 'unique.module.name.18'; backend dependencies: dependency 'com.jetbrains.remoteDevelopment' from descriptor 'plugin.xml' in module 'unique.module.name.18'">idea-plugin</error>>
          <dependencies>
            <plugin id="com.intellij.jetbrains.client"/>
            <plugin id="com.jetbrains.remoteDevelopment"/>
          </dependencies>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testMixedDependsInPluginXml() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.28",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      pluginXmlContent = """
        <<error descr="This module effectively depends on frontend-only and backend-only modules simultaneously. It will not get loaded in runtime. Reason: frontend dependencies: dependency 'com.intellij.jetbrains.client' from descriptor 'plugin.xml' in module 'unique.module.name.28'; backend dependencies: dependency 'com.jetbrains.remoteDevelopment' from descriptor 'plugin.xml' in module 'unique.module.name.28'">idea-plugin</error>>
          <depends>com.intellij.jetbrains.client</depends>
          <depends>com.jetbrains.remoteDevelopment</depends>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testMixedDependenciesInContentModuleXml() {
    val contentModuleDescriptor = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.19",
      descriptorRelativePathToResourcesDirectory = "unique.module.name.19.xml",
      pluginXmlContent = """
        <<error descr="This module effectively depends on frontend-only and backend-only modules simultaneously. It will not get loaded in runtime. Reason: frontend dependencies: dependency 'intellij.platform.frontend.split' from descriptor 'unique.module.name.19.xml' in module 'unique.module.name.19'; backend dependencies: dependency 'intellij.platform.kernel.backend' from descriptor 'unique.module.name.19.xml' in module 'unique.module.name.19'">idea-plugin</error>>
          <dependencies>
            <module name="intellij.platform.frontend.split"/>
            <module name="intellij.platform.kernel.backend"/>
          </dependencies>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(contentModuleDescriptor.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testOnlyFrontendDependenciesAreAllowed() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.20",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      pluginXmlContent = """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.frontend"/>
            <module name="intellij.platform.plugins.frontend.split"/>
            <plugin id="com.intellij.jetbrains.client"/>
          </dependencies>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testMixedDependenciesInContentModuleOfBackendOnlyPlugin() {
    addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.21",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      pluginXmlContent = """
        <idea-plugin>
          <id>com.example.backend.plugin</id>
          <dependencies>
            <module name="intellij.platform.backend"/>
          </dependencies>
          <content>
            <module name="unique.module.name.22"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    val contentModuleDescriptor = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.22",
      descriptorRelativePathToResourcesDirectory = "unique.module.name.22.xml",
      pluginXmlContent = """
        <<error descr="This module effectively depends on frontend-only and backend-only modules simultaneously. It will not get loaded in runtime. Reason: frontend dependencies: dependency 'intellij.platform.plugins.frontend.split' from descriptor 'unique.module.name.22.xml' in module 'unique.module.name.22'; backend dependencies: dependency 'intellij.platform.backend' from containing plugin descriptor 'plugin.xml' in module 'unique.module.name.21'">idea-plugin</error>>
          <dependencies>
            <module name="intellij.platform.plugins.frontend.split"/>
          </dependencies>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(contentModuleDescriptor.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testMixedDependenciesInContentModuleOfFrontendOnlyPlugin() {
    addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.23",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      pluginXmlContent = """
        <idea-plugin>
          <id>com.example.frontend.plugin</id>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
          <content>
            <module name="unique.module.name.24"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    val contentModuleDescriptor = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.24",
      descriptorRelativePathToResourcesDirectory = "unique.module.name.24.xml",
      pluginXmlContent = """
        <<error descr="This module effectively depends on frontend-only and backend-only modules simultaneously. It will not get loaded in runtime. Reason: frontend dependencies: dependency 'intellij.platform.frontend' from containing plugin descriptor 'plugin.xml' in module 'unique.module.name.23'; backend dependencies: dependency 'intellij.platform.kernel.backend' from descriptor 'unique.module.name.24.xml' in module 'unique.module.name.24'">idea-plugin</error>>
          <dependencies>
            <module name="intellij.platform.kernel.backend"/>
          </dependencies>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(contentModuleDescriptor.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testMixedContentModuleWithoutOwnDependenciesBlock() {
    addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.25",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      pluginXmlContent = """
        <idea-plugin>
          <id>com.example.frontend.plugin</id>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
          <content>
            <module name="unique.module.name.27"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.26",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      pluginXmlContent = """
        <idea-plugin>
          <id>com.example.backend.plugin</id>
          <dependencies>
            <module name="intellij.platform.backend"/>
          </dependencies>
          <content>
            <module name="unique.module.name.27"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    val contentModuleDescriptor = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.27",
      descriptorRelativePathToResourcesDirectory = "unique.module.name.27.xml",
      pluginXmlContent = """
        <<error descr="This module effectively depends on frontend-only and backend-only modules simultaneously. It will not get loaded in runtime. Reason: frontend dependencies: dependency 'intellij.platform.frontend' from containing plugin descriptor 'plugin.xml' in module 'unique.module.name.25'; backend dependencies: dependency 'intellij.platform.backend' from containing plugin descriptor 'plugin.xml' in module 'unique.module.name.26'">idea-plugin</error>>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(contentModuleDescriptor.virtualFile)

    myFixture.checkHighlighting()
  }

  private fun addModuleWithXmlDescriptor(
    moduleName: String,
    descriptorRelativePathToResourcesDirectory: String,
    pluginXmlContent: String,
  ): PsiFile {
    val addedModule =
      PsiTestUtil.addModule(project, PluginModuleType.getInstance(), moduleName, myFixture.tempDirFixture.findOrCreateDir(moduleName))
    PsiTestUtil.addSourceRoot(
      addedModule,
      myFixture.tempDirFixture.findOrCreateDir("$moduleName/resources"),
      JavaResourceRootType.RESOURCE,
    )
    val createdDescriptorFile = myFixture.addFileToProject("$moduleName/resources/$descriptorRelativePathToResourcesDirectory", pluginXmlContent)
    Assert.assertNotNull("XML descriptor for module $moduleName was not created", createdDescriptorFile)
    if (descriptorRelativePathToResourcesDirectory == "META-INF/plugin.xml") {
      val buildConfiguration = PluginBuildConfiguration.getInstance(addedModule)
      Assert.assertNotNull("Plugin build configuration for module $moduleName was not created", buildConfiguration)
      buildConfiguration!!.setPluginXmlFromVirtualFile(createdDescriptorFile!!.virtualFile)
    }
    return createdDescriptorFile!!
  }
}
