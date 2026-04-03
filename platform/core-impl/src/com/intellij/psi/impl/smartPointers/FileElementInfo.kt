// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.impl.smartPointers

import com.intellij.codeInsight.multiverse.CodeInsightContext
import com.intellij.codeInsight.multiverse.codeInsightContext
import com.intellij.lang.Language
import com.intellij.lang.LanguageUtil
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Segment
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.PsiDocumentManagerEx

/**
 * Tracks an entire [PsiFile].
 * 
 * 
 * Stores the virtual file, language ID, and file class name;
 * verifies the class name matches on restoration to handle multi-language view providers correctly.
 */
internal class FileElementInfo(file: PsiFile) : SmartPointerElementInfo, ContextAwareInfo {
  override val virtualFile: VirtualFile = file.viewProvider.virtualFile
  private val myProject: Project = file.project
  private val myLanguageId: String = LanguageUtil.getRootLanguage(file).id
  private val myFileClassName: String = file.javaClass.name
  private val myContext: CodeInsightContext = file.viewProvider.codeInsightContext

  override fun restoreElement(manager: SmartPointerManagerEx): PsiElement? {
    val language = Language.findLanguageByID(myLanguageId) ?: return null
    val file = SelfElementInfo.restoreFileFromVirtual(this.virtualFile, myContext, myProject, language) ?: return null
    return if (file.javaClass.name == myFileClassName) file else null
  }

  override fun restoreFile(manager: SmartPointerManagerEx): PsiFile? {
    val element = restoreElement(manager)
    return element?.getContainingFile() // can be directory
  }

  override fun elementHashCode(): Int = virtualFile.hashCode()

  override fun pointsToTheSameElementAs(other: SmartPointerElementInfo, manager: SmartPointerManagerEx): Boolean =
    other is FileElementInfo && this.virtualFile == other.virtualFile

  override fun getRange(manager: SmartPointerManagerEx): Segment? {
    if (!virtualFile.isValid()) return null

    val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return null
    return TextRange.from(0, document.textLength)
  }

  override fun getPsiRange(manager: SmartPointerManagerEx): Segment? {
    val currentDoc = FileDocumentManager.getInstance().getCachedDocument(virtualFile)
    val committedDoc = currentDoc?.let { (PsiDocumentManager.getInstance(myProject) as PsiDocumentManagerEx).getLastCommittedDocument(it) }
    return if (committedDoc == null) getRange(manager) else TextRange(0, committedDoc.textLength)
  }

  override fun toString(): String = "file{$virtualFile, $myLanguageId}"
}
