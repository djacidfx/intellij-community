// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.impl.file.impl

import com.intellij.codeInsight.multiverse.*
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.projectStructure.fixture.multiverseProjectFixture
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiInvalidElementAccessException
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.PsiManagerImpl
import com.intellij.psi.util.PsiUtilCore.ensureValid
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.LogLevelWithClass
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.fileOrDirInProjectFixture
import com.intellij.testFramework.junit5.fixture.moduleInProjectFixture
import com.intellij.util.ExceptionUtil
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail

@LogLevelWithClass(
  category = CodeInsightContextManagerImpl::class,
  level = LogLevel.TRACE
)
@com.intellij.testFramework.junit5.LogLevel(
  category = "#com.intellij.psi.impl.file.impl.MultiverseFileViewProviderCache",
  level = LogLevel.TRACE
)
@TestApplication
internal class FileMoveTest {
  private val projectFixture = multiverseProjectFixture(openAfterCreation = true) {
    module("module1") {
      contentRoot("contentRoot1") {
        sourceRoot("src1") {
          file("A.java", "public class A {}")
        }
        sourceRoot("src1-2") {
        }
      }
    }
    module("module2") {
      contentRoot("contentRoot2") {
        sourceRoot("src2") {
        }
      }
    }
    module("module3") {
      contentRoot("contentRoot3") {
        sourceRoot("src34", "sharedRoot34") {
          file("B.java", "public class B {}")
        }
        sourceRoot("src34-2", "sharedRoot34-2") {
        }
      }
    }
    module("module4") {
      sharedSourceRoot("sharedRoot34")
      sharedSourceRoot("sharedRoot34-2")
      contentRoot("contentRoot4") {
        sourceRoot("src4") {
        }
      }
    }
  }

  private val project by projectFixture

  private val aJava by projectFixture.fileOrDirInProjectFixture("module1/contentRoot1/src1/A.java")
  private val bJava by projectFixture.fileOrDirInProjectFixture("module3/contentRoot3/src34/B.java")

  private val src2 by projectFixture.fileOrDirInProjectFixture("module2/contentRoot2/src2")
  private val src4 by projectFixture.fileOrDirInProjectFixture("module4/contentRoot4/src4")
  private val src12 by projectFixture.fileOrDirInProjectFixture("module1/contentRoot1/src1-2")
  private val src34 by projectFixture.fileOrDirInProjectFixture("module3/contentRoot3/src34-2")

  private val module3 by projectFixture.moduleInProjectFixture("module3")
  private val module4 by projectFixture.moduleInProjectFixture("module4")

  @Test
  fun `file changes context after move`() = doTest {
    val psiFile = aJava.findPsiFile()

    assertModuleContext(psiFile, aJava, "module1")

    moveFile(aJava, src2)

    assertPsiFileIsValid(psiFile, aJava)

    val movedPsiFile = aJava.findPsiFile()
    assertModuleContext(movedPsiFile, aJava, "module2")
  }

  @Test
  fun `file changes context after move for any-context`() = doTest {
    val psiFile = aJava.findPsiFile()

    val rawContext = CodeInsightContextManagerImpl.getInstanceImpl(project).getCodeInsightContextRaw(psiFile.viewProvider)
    assert(rawContext == anyContext()) { psiFile.presentableTextWithContext() + ", " + dumpPsiFiles(aJava) }

    moveFile(aJava, src2)

    assertPsiFileIsValid(psiFile, aJava)

    val movedPsiFile = aJava.findPsiFile()
    assertEquals("module2", movedPsiFile.getModuleContextName()) { dumpPsiFiles(bJava) }
  }

  @Test
  fun `shared file changes context after move`() = doTest {
    val psiFile3 = bJava.findPsiFile(module3.asContext())
    val psiFile4 = bJava.findPsiFile(module4.asContext())

    assertEquals("module3", psiFile3.getModuleContextName())
    assertEquals("module4", psiFile4.getModuleContextName())

    moveFile(bJava, src2)

    readAction {
      assert(psiFile3.isValid xor psiFile4.isValid) {
        val text3 = psiFile3.presentableTextWithContext() + "[" + if (psiFile3.isValid) "valid]" else "invalid]"
        val text4 = psiFile3.presentableTextWithContext() + "[" + if (psiFile4.isValid) "valid]" else "invalid]"
        text3 + " : " + text4 + ", " + dumpPsiFiles(bJava)
      }
    }

    val movedPsiFile = bJava.findPsiFile()
    assertModuleContext(movedPsiFile, bJava, "module2")
  }

  @Test
  fun `shared file changes context after move when one context survives`() = doTest { // this
    val psiFile3 = bJava.findPsiFile(module3.asContext())
    val psiFile4 = bJava.findPsiFile(module4.asContext())

    assertEquals("module3", psiFile3.getModuleContextName())
    assertEquals("module4", psiFile4.getModuleContextName())

    moveFile(bJava, src4)

    assertPsiFileIsNotValid(psiFile3, bJava)
    assertPsiFileIsValid(psiFile4, bJava)

    val movedPsiFile = bJava.findPsiFile()
    assertModuleContext(movedPsiFile, bJava, "module4")
  }

  @Test
  fun `shared file moves and all contexts survive`() = doTest {
    val module3 = ModuleManager.getInstance(project).findModuleByName("module3")!!
    val module4 = ModuleManager.getInstance(project).findModuleByName("module4")!!
    val module3Context = ProjectModelContextBridge.getInstance(project).getContext(module3)!!
    val module4Context = ProjectModelContextBridge.getInstance(project).getContext(module4)!!

    val psiFile3 = bJava.findPsiFile(module3Context)
    val psiFile4 = bJava.findPsiFile(module4Context)

    assertModuleContext(psiFile3, bJava, "module3")
    assertModuleContext(psiFile4, bJava, "module4")

    moveFile(bJava, src34)

    assertPsiFileIsValid(psiFile3, bJava)
    assertPsiFileIsValid(psiFile4, bJava)
  }

  @Test
  fun `file survives move`() = doTest {
    val psiFile = aJava.findPsiFile()
    assertModuleContext(psiFile, aJava, "module1")

    moveFile(aJava, src12)

    assertPsiFileIsValid(psiFile, aJava)
    assertModuleContext(psiFile, aJava, "module1")
  }

  private suspend fun VirtualFile.findPsiFile(): PsiFile {
    return readAction {
      PsiManager.getInstance(projectFixture.get()).findFile(this) ?: throw IllegalStateException("PsiFile not found: $this")
    }
  }

  private suspend fun VirtualFile.findPsiFile(context: CodeInsightContext): PsiFile {
    return readAction {
      PsiManager.getInstance(projectFixture.get()).findFile(this, context) ?: throw IllegalStateException("PsiFile not found: $this")
    }
  }

  private suspend fun PsiFile.getModuleContextName(): String {
    return readAction {
      (codeInsightContext as ModuleContext).getModule()!!.name
    }
  }

  private fun Module.asContext(): ModuleContext = ProjectModelContextBridge.getInstance(project).getContext(this)!!

  private fun doTest(block: suspend () -> Unit) = timeoutRunBlocking {
    thisLogger().debug("test started")
    try {
      block()
    }
    finally {
      thisLogger().debug("test finished")
    }
  }

  private suspend fun moveFile(file: VirtualFile, target: VirtualFile) {
    writeAction {
      val message = dumpPsiFiles(file)
      thisLogger().debug("Existing PsiFiles of ${file.path}: " + message)

      thisLogger().debug("moving ${file.path} to ${target.path}")
      file.move(this, target)
    }
  }

  private suspend fun assertPsiFileIsValid(psiFile: PsiFile, vFile: VirtualFile) {
    readAction {
      if (!psiFile.isValid) {
        val info = prepareInvalidationInfo(psiFile)
        fail("File ${psiFile.presentableTextWithContext()} must be valid. See registered files:" + dumpPsiFiles(vFile) + info)
      }
    }
  }

  private suspend fun assertPsiFileIsNotValid(psiFile: PsiFile, vFile: VirtualFile) {
    readAction {
      if (psiFile.isValid) {
        fail("File ${psiFile.presentableTextWithContext()} must be invalid. See registered files:" + dumpPsiFiles(vFile))
      }
    }
  }

  private suspend fun assertModuleContext(psiFile: PsiFile, vFile: VirtualFile, expectedContext: String) {
    assertEquals(expectedContext, psiFile.getModuleContextName()) { dumpPsiFiles(vFile) }
  }

  private fun dumpPsiFiles(file: VirtualFile): String {
    val allCachedFiles = PsiManagerImpl.getInstanceEx(project).fileManagerEx.allCachedFiles
    val myPsiFiles = allCachedFiles.filter { it.virtualFile == file }

    return myPsiFiles.joinToString(separator = "\n  ", prefix = "[\n  ", postfix = "\n]") { file ->
      file.presentableTextWithContext()
    }
  }

  private fun PsiFile.presentableTextWithContext(): String {
    val m = CodeInsightContextManagerImpl.getInstanceImpl(project)
    val rawContext = m.getCodeInsightContextRaw(this.viewProvider)
    return rawContext.presentableToString() + " : " + this.presentableText()
  }

  private fun CodeInsightContext.presentableToString(): String {
    return when (this) {
      is ModuleContext -> "ModuleContext(module=${getModule()!!.name})"
      else -> this.toString()
    }
  }

  private fun PsiFile.presentableText(): String {
    return "File@" + System.identityHashCode(this)
  }

  private fun prepareInvalidationInfo(invalidPsi: PsiFile): String {
    val invalidationException = runCatching {
      ensureValid(invalidPsi)
    }

    val t = invalidationException.exceptionOrNull() ?: return ""
    val e = ExceptionUtil.findCause(t, PsiInvalidElementAccessException::class.java) ?: return ""
    if (e.attachments.isEmpty()) return ""

    val attachments = e.attachments.joinToString(prefix = "\nDiagnostics:\n\n", separator = "\n\n") { it.displayText }
    return attachments
  }
}
