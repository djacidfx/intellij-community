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
import org.jetbrains.idea.devkit.inspections.remotedev.SplitModeXmlApiUsageInspection
import org.jetbrains.idea.devkit.module.PluginModuleType
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.junit.Assert
import kotlin.time.Duration.Companion.seconds

internal class SplitModeXmlApiUsageInspectionTest : JavaCodeInsightFixtureTestCase() {
  override fun setUp() {
    super.setUp()
    IntelliJProjectUtil.markAsIntelliJPlatformProject(project, true)

    val service = SplitModeApiRestrictionsService.getInstance()
    service.scheduleLoadRestrictions()
    timeoutRunBlocking {
      waitUntil("API restrictions failed to load", 2.seconds) { service.isLoaded() }
    }

    myFixture.enableInspections(SplitModeXmlApiUsageInspection(), SplitModeMixedDependenciesInspection())
  }

  fun testFrontendExtensionInBackendModule() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.1",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.backend"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.fileEditorProvider' can only be used in 'frontend' module type. Actual module type is 'backend'. Reason: backend dependencies: dependency 'intellij.platform.backend' from descriptor 'plugin.xml' in module 'unique.module.name.1'">fileEditorProvider</warning>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testBackendExtensionInFrontendModule() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.2",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.localInspection' can only be used in 'backend' module type. Actual module type is 'frontend'. Reason: frontend dependencies: dependency 'intellij.platform.frontend' from descriptor 'plugin.xml' in module 'unique.module.name.2'">localInspection</warning>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testFrontendAndBackendExtensionsInSharedModule() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.3",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.core"/>
            <module name="intellij.platform.ide"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.fileEditorProvider' can only be used in 'frontend' module type. Actual module type is 'shared'. Reason: no frontend or backend dependencies were found among: 'intellij.platform.core', 'intellij.platform.ide'">fileEditorProvider</warning>/>
            <<warning descr="'com.intellij.localInspection' can only be used in 'backend' module type. Actual module type is 'shared'. Reason: no frontend or backend dependencies were found among: 'intellij.platform.core', 'intellij.platform.ide'">localInspection</warning>/>
            <lang.parserDefinition/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testBackendExtensionInFrontendContentModule() {
    val contentModuleDescriptor = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.4",
      descriptorRelativePathToResourcesDirectory = "unique.module.name.4.xml",
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.localInspection' can only be used in 'backend' module type. Actual module type is 'frontend'. Reason: frontend dependencies: dependency 'intellij.platform.frontend' from descriptor 'unique.module.name.4.xml' in module 'unique.module.name.4'">localInspection</warning>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(contentModuleDescriptor.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testFrontendExtensionInContentModuleOfBackendOnlyPlugin() {
    addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.5",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <id>com.example.backend.plugin</id>
          <dependencies>
            <module name="intellij.platform.backend"/>
          </dependencies>
          <content>
            <module name="unique.module.name.6"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    val contentModuleDescriptor = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.6",
      descriptorRelativePathToResourcesDirectory = "unique.module.name.6.xml",
      """
        <idea-plugin>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.fileEditorProvider' can only be used in 'frontend' module type. Actual module type is 'backend'. Reason: module declares no own FE/BE dependencies, but the containing plugin.xml files do: module 'unique.module.name.5'  -> backend">fileEditorProvider</warning>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(contentModuleDescriptor.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testFrontendAndBackendExtensionsInMixedContentModuleWithMultipleContainingPlugins() {
    addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.7",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <id>com.example.frontend.plugin</id>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
          <content>
            <module name="unique.module.name.9"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.8",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <id>com.example.backend.plugin</id>
          <dependencies>
            <module name="intellij.platform.backend"/>
          </dependencies>
          <content>
            <module name="unique.module.name.9"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    val contentModuleDescriptor = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.9",
      descriptorRelativePathToResourcesDirectory = "unique.module.name.9.xml",
      """
        <<error descr="This module mixes frontend-only dependencies (intellij.platform.frontend) and backend-only dependencies (intellij.platform.backend)">idea-plugin</error>>
          <extensions defaultExtensionNs="com.intellij">
            <fileEditorProvider/>
            <localInspection/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(contentModuleDescriptor.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testFrontendAndBackendExtensionsInMixedModule() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.10",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <<error descr="This module mixes frontend-only dependencies (intellij.platform.frontend) and backend-only dependencies (intellij.platform.backend)">idea-plugin</error>>
          <dependencies>
            <module name="intellij.platform.frontend"/>
            <module name="intellij.platform.backend"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <fileEditorProvider/>
            <localInspection/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testFrontendExtensionInTransitivelyFrontendModule() {
    addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.11",
      descriptorRelativePathToResourcesDirectory = "unique.module.name.11.xml",
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
        </idea-plugin>
      """.trimIndent()
    )
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.12",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.transitive.frontend"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <fileEditorProvider/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testNoWarningsForFrontendExtensionInSingleModuleExternalPluginWithBackendVcsDependency() {
    IntelliJProjectUtil.markAsIntelliJPlatformProject(project, false)
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.13",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.vcs.impl"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <fileEditorProvider/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testBackendExtensionInContentModuleWithMultipleContainingFrontendPlugins() {
    addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.14",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <id>com.example.frontend.plugin.one</id>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
          <content>
            <module name="unique.module.name.16"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.15",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <id>com.example.frontend.plugin.two</id>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
          <content>
            <module name="unique.module.name.16"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    val contentModuleDescriptor = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.16",
      descriptorRelativePathToResourcesDirectory = "unique.module.name.16.xml",
      """
        <idea-plugin>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.localInspection' can only be used in 'backend' module type. Actual module type is 'frontend'. Reason: module declares no own FE/BE dependencies, but the containing plugin.xml files do: module 'unique.module.name.14'  -> frontend, module 'unique.module.name.15'  -> frontend">localInspection</warning>/>
          </extensions>
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
    PsiTestUtil.addSourceRoot(addedModule,
                              myFixture.tempDirFixture.findOrCreateDir("$moduleName/resources"),
                              JavaResourceRootType.RESOURCE)
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
