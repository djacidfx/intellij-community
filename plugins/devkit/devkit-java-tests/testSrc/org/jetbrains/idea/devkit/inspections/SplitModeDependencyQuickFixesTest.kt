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

internal class SplitModeDependencyQuickFixesTest : JavaCodeInsightFixtureTestCase() {
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

  fun testAddFrontendDependencyFixInXmlInspection() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.quick.fix.1",
      pluginXmlContent = """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.core"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <fileEditorProvider<caret>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    val intention = myFixture.findSingleIntention("Make module 'unique.module.name.quick.fix.1' work in 'frontend' only")
    myFixture.launchAction(intention)

    val result = myFixture.file.text
    Assert.assertTrue(result.contains("<module name=\"intellij.platform.core\"/>"))
    Assert.assertTrue(result.contains("<module name=\"intellij.platform.frontend\"/>"))
  }

  fun testMakeModuleFrontendDependenciesFixInXmlInspection() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.quick.fix.2",
      pluginXmlContent = """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.core"/>
            <module name="intellij.platform.backend"/>
            <plugin id="com.jetbrains.remoteDevelopment"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <fileEditorProvider<caret>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    val intention = myFixture.findSingleIntention("Make module 'unique.module.name.quick.fix.2' work in 'frontend' only")
    myFixture.launchAction(intention)

    val result = myFixture.file.text
    Assert.assertTrue(result.contains("<module name=\"intellij.platform.core\"/>"))
    Assert.assertFalse(result.contains("intellij.platform.backend"))
    Assert.assertFalse(result.contains("com.jetbrains.remoteDevelopment"))
  }

  fun testMakeModuleFrontendDependenciesFixInMixedInspection() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.quick.fix.3",
      pluginXmlContent = """
        <idea-<caret>plugin>
          <dependencies>
            <module name="intellij.platform.core"/>
            <module name="intellij.platform.frontend"/>
            <module name="intellij.platform.backend"/>
            <plugin id="com.intellij.jetbrains.client"/>
            <plugin id="com.jetbrains.remoteDevelopment"/>
          </dependencies>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    val intention = myFixture.findSingleIntention("Make module 'unique.module.name.quick.fix.3' work in 'frontend' only")
    myFixture.launchAction(intention)

    val result = myFixture.file.text
    Assert.assertTrue(result.contains("<module name=\"intellij.platform.core\"/>"))
    Assert.assertTrue(result.contains("<module name=\"intellij.platform.frontend\"/>"))
    Assert.assertTrue(result.contains("<plugin id=\"com.intellij.jetbrains.client\"/>"))
    Assert.assertFalse(result.contains("intellij.platform.backend"))
    Assert.assertFalse(result.contains("com.jetbrains.remoteDevelopment"))
  }

  fun testMakeModuleMonolithOnlyFixInXmlInspection() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.quick.fix.4",
      pluginXmlContent = """
        <idea-plugin>
          <dependencies>
            <module name="intellij.platform.core"/>
          </dependencies>
          <extensions defaultExtensionNs="com.intellij">
            <fileEditorProvider<caret>/>
          </extensions>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    val intention = myFixture.findSingleIntention("Make module 'unique.module.name.quick.fix.4' work in 'monolith' only")
    myFixture.launchAction(intention)

    val result = myFixture.file.text
    Assert.assertTrue(result.contains("<module name=\"intellij.platform.core\"/>"))
    Assert.assertTrue(result.contains("<module name=\"intellij.platform.monolith\"/>"))
  }

  fun testMakeModuleMonolithOnlyFixInMixedInspection() {
    val pluginXml = addModuleWithXmlDescriptor(
      moduleName = "unique.module.name.quick.fix.5",
      pluginXmlContent = """
        <idea-<caret>plugin>
          <dependencies>
            <module name="intellij.platform.core"/>
            <module name="intellij.platform.frontend"/>
            <module name="intellij.platform.backend"/>
            <plugin id="com.intellij.jetbrains.client"/>
            <plugin id="com.jetbrains.remoteDevelopment"/>
          </dependencies>
        </idea-plugin>
      """.trimIndent()
    )
    myFixture.configureFromExistingVirtualFile(pluginXml.virtualFile)

    val intention = myFixture.filterAvailableIntentions("Make module 'unique.module.name.quick.fix.5' work in 'monolith' only").single()
    myFixture.launchAction(intention)

    val result = myFixture.file.text
    Assert.assertTrue(result.contains("<module name=\"intellij.platform.core\"/>"))
    Assert.assertTrue(result.contains("<module name=\"intellij.platform.monolith\"/>"))
    Assert.assertFalse(result.contains("intellij.platform.frontend"))
    Assert.assertFalse(result.contains("intellij.platform.backend"))
    Assert.assertTrue(result.contains("<plugin id=\"com.intellij.jetbrains.client\"/>"))
    Assert.assertTrue(result.contains("<plugin id=\"com.jetbrains.remoteDevelopment\"/>"))
    myFixture.checkHighlighting()
  }

  private fun addModuleWithXmlDescriptor(
    moduleName: String,
    pluginXmlContent: String,
  ): PsiFile {
    val addedModule =
      PsiTestUtil.addModule(project, PluginModuleType.getInstance(), moduleName, myFixture.tempDirFixture.findOrCreateDir(moduleName))
    PsiTestUtil.addSourceRoot(
      addedModule,
      myFixture.tempDirFixture.findOrCreateDir("$moduleName/resources"),
      JavaResourceRootType.RESOURCE,
    )
    val createdDescriptorFile = myFixture.addFileToProject("$moduleName/resources/META-INF/plugin.xml", pluginXmlContent)
    Assert.assertNotNull("XML descriptor for module $moduleName was not created", createdDescriptorFile)
    val buildConfiguration = PluginBuildConfiguration.getInstance(addedModule)
    Assert.assertNotNull("Plugin build configuration for module $moduleName was not created", buildConfiguration)
    buildConfiguration!!.setPluginXmlFromVirtualFile(createdDescriptorFile!!.virtualFile)
    return createdDescriptorFile
  }
}
