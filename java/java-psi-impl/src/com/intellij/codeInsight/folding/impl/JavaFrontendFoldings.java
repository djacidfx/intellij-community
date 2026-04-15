// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.folding.impl;

import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiJavaModule;
import org.jetbrains.annotations.NotNull;

import java.util.List;

final class JavaFrontendFoldings {
  static void buildFrontendFoldRegions(@NotNull List<FoldingDescriptor> descriptors,
                                       @NotNull PsiJavaFile file,
                                       @NotNull Document document,
                                       boolean quick) {
    JavaFoldingUtil.addFoldsForImports(descriptors, file);

    PsiJavaModule module = file.getModuleDeclaration();
    if (module != null) {
      JavaFoldingUtil.addFoldsForModule(descriptors, module, document);
    }


    JavaFoldingUtil.addFoldsForFileHeader(descriptors, file, document);
    JavaFoldingUtil.addCommentsToFold(descriptors, file, document);
  }
}
