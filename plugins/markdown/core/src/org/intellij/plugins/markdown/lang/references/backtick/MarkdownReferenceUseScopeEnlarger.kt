// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.plugins.markdown.lang.references.backtick

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.UseScopeEnlarger
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import org.intellij.plugins.markdown.lang.MarkdownFileType

internal class MarkdownReferenceUseScopeEnlarger : UseScopeEnlarger() {
  override fun getAdditionalUseScope(element: PsiElement): SearchScope? {
    if (element !is PsiNamedElement) return null
    if (element.useScope !is GlobalSearchScope) return null
    if (!hasMarkdownFiles(element.project)) return null

    return GlobalSearchScope.getScopeRestrictedByFileTypes(
      GlobalSearchScope.projectScope(element.project),
      MarkdownFileType.INSTANCE
    )
  }

  private fun hasMarkdownFiles(project: Project): Boolean {
    return CachedValuesManager.getManager(project).getCachedValue(project) {
      CachedValueProvider.Result.create(
        FileTypeIndex.containsFileOfType(MarkdownFileType.INSTANCE, GlobalSearchScope.projectScope(project)),
        PsiModificationTracker.MODIFICATION_COUNT
      )
    }
  }
}
