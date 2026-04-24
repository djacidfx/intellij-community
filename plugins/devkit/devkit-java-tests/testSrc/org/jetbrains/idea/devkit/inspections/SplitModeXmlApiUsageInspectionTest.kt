// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections

import com.intellij.openapi.project.IntelliJProjectUtil
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.common.waitUntil
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase
import org.jetbrains.idea.devkit.inspections.remotedev.SplitModeApiRestrictionsService
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

    PsiTestUtil.addResourceContentToRoots(module, myFixture.tempDirFixture.findOrCreateDir("resources"), false)
    myFixture.enableInspections(SplitModeXmlApiUsageInspection())
  }

  fun testFrontendExtensionInBackendModule() {
    configurePluginXml(
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.backend"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.fileEditorProvider' can only be used in 'frontend' module type. Actual module type is 'backend'">fileEditorProvider</warning>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )

    myFixture.checkHighlighting()
  }

  fun testBackendExtensionInFrontendModule() {
    configurePluginXml(
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.localInspection' can only be used in 'backend' module type. Actual module type is 'frontend'">localInspection</warning>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )

    myFixture.checkHighlighting()
  }

  fun testFrontendAndBackendExtensionsInSharedModule() {
    configurePluginXml(
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.core"/>
            <module name="intellij.platform.ide"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.fileEditorProvider' can only be used in 'frontend' module type. Actual module type is 'shared'">fileEditorProvider</warning>/>
            <<warning descr="'com.intellij.localInspection' can only be used in 'backend' module type. Actual module type is 'shared'">localInspection</warning>/>
            <lang.parserDefinition/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )

    myFixture.checkHighlighting()
  }

  fun testBackendExtensionInFrontendContentModule() {
    configureContentModuleXml(
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.localInspection' can only be used in 'backend' module type. Actual module type is 'frontend'">localInspection</warning>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )

    myFixture.checkHighlighting()
  }

  fun testFrontendExtensionInContentModuleOfBackendOnlyPlugin() {
    addModuleWithXmlDescriptor(
      moduleName = "com.example.backend.plugin",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <id>com.example.backend.plugin</id>
          <dependencies>
            <module name="intellij.platform.backend"/>
          </dependencies>
          <content>
            <module name="com.example.content.module"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    val contentModuleDescriptor = addModuleWithXmlDescriptor(
      moduleName = "com.example.content.module",
      descriptorRelativePathToResourcesDirectory = "com.example.content.module.xml",
      """
        <idea-plugin>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.fileEditorProvider' can only be used in 'frontend' module type. Actual module type is 'backend'">fileEditorProvider</warning>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(contentModuleDescriptor.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testFrontendExtensionInContentModuleWithMultipleContainingPlugins() {
    addModuleWithXmlDescriptor(
      moduleName = "com.example.frontend.plugin",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <id>com.example.frontend.plugin</id>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
          <content>
            <module name="com.example.shared.content.module"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    addModuleWithXmlDescriptor(
      moduleName = "com.example.backend.plugin",
      descriptorRelativePathToResourcesDirectory = "META-INF/plugin.xml",
      """
        <idea-plugin>
          <id>com.example.backend.plugin</id>
          <dependencies>
            <module name="intellij.platform.backend"/>
          </dependencies>
          <content>
            <module name="com.example.shared.content.module"/>
          </content>
        </idea-plugin>
      """.trimIndent()
    )
    val contentModuleDescriptor = addModuleWithXmlDescriptor(
      moduleName = "com.example.shared.content.module",
      descriptorRelativePathToResourcesDirectory = "com.example.shared.content.module.xml",
      """
        <idea-plugin>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.fileEditorProvider' can only be used in 'frontend' module type. Actual module type is 'shared'">fileEditorProvider</warning>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(contentModuleDescriptor.virtualFile)

    myFixture.checkHighlighting()
  }

  fun testFrontendAndBackendExtensionsInMixedModule() {
    configurePluginXml(
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.frontend"/>
            <module name="intellij.platform.backend"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <<warning descr="'com.intellij.fileEditorProvider' can only be used in 'frontend' module type. Actual module type is 'mixed'">fileEditorProvider</warning>/>
            <<warning descr="'com.intellij.localInspection' can only be used in 'backend' module type. Actual module type is 'mixed'">localInspection</warning>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )

    myFixture.checkHighlighting()
  }

  fun testFrontendExtensionInTransitivelyFrontendModule() {
    PsiTestUtil.addModule(
      project,
      PluginModuleType.getInstance(),
      "intellij.transitive.frontend",
      myFixture.tempDirFixture.findOrCreateDir("intellij.transitive.frontend")
    )
    myFixture.addFileToProject(
      "intellij.transitive.frontend/intellij.transitive.frontend.xml",
      """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.frontend"/>
          </dependencies>
        </idea-plugin>
      """.trimIndent()
    )
    configurePluginXml(
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

    myFixture.checkHighlighting()
  }

  fun testNoWarningsForFrontendExtensionInSingleModuleExternalPluginWithBackendVcsDependency() {
    IntelliJProjectUtil.markAsIntelliJPlatformProject(project, false)
    configurePluginXml(
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

    myFixture.checkHighlighting()
  }

  private fun configurePluginXml(pluginXmlContent: String) {
    configureDescriptor("resources/META-INF/plugin.xml", pluginXmlContent)
  }

  private fun configureContentModuleXml(pluginXmlContent: String) {
    configureDescriptor("resources/light_idea_test_case.xml", pluginXmlContent)
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
    return createdDescriptorFile!!
  }

  private fun configureDescriptor(relativePath: String, pluginXmlContent: String) {
    val descriptor = myFixture.addFileToProject(relativePath, pluginXmlContent)
    myFixture.configureFromExistingVirtualFile(descriptor.virtualFile)
  }
}
