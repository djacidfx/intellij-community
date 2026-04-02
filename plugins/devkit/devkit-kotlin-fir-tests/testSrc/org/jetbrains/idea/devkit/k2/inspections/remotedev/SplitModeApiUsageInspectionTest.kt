// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.kotlin.inspections.remotedev

import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.common.waitUntil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.idea.devkit.inspections.remotedev.ApiRestrictionsService
import org.jetbrains.idea.devkit.inspections.remotedev.SplitModeApiUsageInspection
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.jetbrains.kotlin.idea.test.ExpectedPluginModeProvider
import org.jetbrains.kotlin.idea.test.setUpWithKotlinPlugin
import kotlin.time.Duration.Companion.seconds

class SplitModeApiUsageInspectionTest : LightJavaCodeInsightFixtureTestCase(), ExpectedPluginModeProvider {

  override val pluginMode: KotlinPluginMode = KotlinPluginMode.K1

  override fun getBasePath(): String = "inspections/apiUsageRestrictedToModuleType"

  override fun setUp() {
    setUpWithKotlinPlugin { super.setUp() }

    val service = ApiRestrictionsService.getInstance()
    service.scheduleLoadRestrictions()
    timeoutRunBlocking {
      waitUntil("API restrictions failed to load", 2.seconds) { service.isLoaded() }
    }

    PsiTestUtil.addResourceContentToRoots(module, myFixture.tempDirFixture.findOrCreateDir("resources"), false)

    // Frontend API: com.intellij.openapi.wm.ToolWindowFactory
    myFixture.addClass("""
      package com.intellij.openapi.wm;
            
      public interface ToolWindowFactory {}
    """.trimIndent())

    myFixture.addClass("""
      package com.intellij.openapi.fileEditor;
            
      public interface FileEditorManager {
        static FileEditorManager getInstance() {
          return null;
        }
        
        void getFocusedEditor();
      }
    """.trimIndent())

    myFixture.addClass("""
      package com.intellij.openapi.wm;
      
      public interface ToolWindow {}
    """.trimIndent())

    myFixture.addClass("""
      package com.intellij.openapi.project;
      
      public interface Project {}
    """.trimIndent())

    // Backend API: com.intellij.openapi.vfs.VirtualFileManager
    myFixture.addClass("""
      package com.intellij.openapi.vfs;
      
      public abstract class VirtualFileManager {
        public static VirtualFileManager getInstance() {
          return null;
        }
        
        public abstract VirtualFile findFileByUrl(String url);
      }
    """.trimIndent())

    myFixture.addClass("""
      package com.intellij.openapi.vfs;
      
      public interface VirtualFile {}
    """.trimIndent())

    myFixture.enableInspections(SplitModeApiUsageInspection())
  }

  private fun configurePluginXml(pluginXmlContent: String) {
    myFixture.addFileToProject("resources/META-INF/plugin.xml", pluginXmlContent)
  }

  private fun configureContentModuleXml(pluginXmlContent: String) {
    myFixture.addFileToProject("resources/light_idea_test_case.xml", pluginXmlContent)
  }

  fun testFrontendApiInBackendModule() {
    configurePluginXml("""
      <idea-plugin>
        <dependencies>
          <module name="intellij.platform.backend"/>
        </dependencies>
      </idea-plugin>
    """.trimIndent())

    myFixture.configureByText("BackendService.kt", """
      package com.example.backend
      
      import com.intellij.openapi.wm.ToolWindowFactory
      import com.intellij.openapi.wm.ToolWindow
      import com.intellij.openapi.project.Project
      import com.intellij.openapi.vfs.VirtualFileManager
      import com.intellij.openapi.fileEditor.FileEditorManager;
      
      class CustomToolWindowFactory: <weak_warning descr="'com.intellij.openapi.wm.ToolWindowFactory' can only be used in 'frontend' module type">ToolWindowFactory</weak_warning> {}
      
      class BackendService {
        fun doStuff() {
          // no warning here expected
          VirtualFileManager.getInstance()
          
          <weak_warning descr="'com.intellij.openapi.fileEditor.FileEditorManager.getFocusedEditor' can only be used in 'frontend' module type">FileEditorManager.getInstance().getFocusedEditor()</weak_warning>
        }
      }
    """.trimIndent())
    myFixture.checkHighlighting()
  }

  fun testBackendApiInFrontendModule() {
    configurePluginXml("""
      <idea-plugin>
        <dependencies>
          <module name="intellij.platform.frontend"/>
        </dependencies>
      </idea-plugin>
    """.trimIndent())

    myFixture.configureByText("FrontendService.kt", """
      package com.example.frontend
      
      import com.intellij.openapi.wm.ToolWindowFactory
      import com.intellij.openapi.wm.ToolWindow
      import com.intellij.openapi.project.Project
      import com.intellij.openapi.vfs.VirtualFileManager
      
      // no warning here expected
      class CustomToolWindowFactory: ToolWindowFactory {}

      class FrontendService {
        fun doStuff() {
          <weak_warning descr="'com.intellij.openapi.vfs.VirtualFileManager' can only be used in 'backend' module type">VirtualFileManager</weak_warning>.getInstance()
        }
      }
    """.trimIndent())

    myFixture.checkHighlighting()
  }

  fun testBackendApiInFrontendContentModule() {
    configureContentModuleXml("""
      <idea-plugin>
        <dependencies>
          <module name="intellij.platform.frontend"/>
        </dependencies>
      </idea-plugin>
    """.trimIndent())

    myFixture.configureByText("FrontendService.kt", """
      package com.example.frontend
      
      import com.intellij.openapi.wm.ToolWindowFactory
      import com.intellij.openapi.wm.ToolWindow
      import com.intellij.openapi.project.Project
      import com.intellij.openapi.vfs.VirtualFileManager
      
      // no warning here expected
      class CustomToolWindowFactory: ToolWindowFactory {}

      class FrontendService {
        fun doStuff() {
          <weak_warning descr="'com.intellij.openapi.vfs.VirtualFileManager' can only be used in 'backend' module type">VirtualFileManager</weak_warning>.getInstance()
        }
      }
    """.trimIndent())

    myFixture.checkHighlighting()
  }


  fun testWarningsInSharedModule() {
    configurePluginXml("""
      <idea-plugin>
        <dependencies>
          <module name="intellij.platform.core"/>
        </dependencies>
      </idea-plugin>
    """.trimIndent())

    myFixture.configureByText("SharedService.kt", """
      package com.example.shared
      
      import com.intellij.openapi.wm.ToolWindowFactory
      import com.intellij.openapi.vfs.VirtualFileManager
      
      // both warnings are expected in a shared module
      class SharedService {
        fun testFrontendApi() {
          class MyToolWindow: <weak_warning descr="'com.intellij.openapi.wm.ToolWindowFactory' can only be used in 'frontend' module type">ToolWindowFactory</weak_warning> {}
        }
        
        fun testBackendApi() {
          <weak_warning descr="'com.intellij.openapi.vfs.VirtualFileManager' can only be used in 'backend' module type">VirtualFileManager</weak_warning>.getInstance()
        }
      }
    """.trimIndent())

    myFixture.checkHighlighting()
  }
}
