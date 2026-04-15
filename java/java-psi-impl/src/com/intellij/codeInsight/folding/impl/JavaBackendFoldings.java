// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.folding.impl;

import com.intellij.codeInsight.folding.JavaCodeFoldingSettings;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiCapturedWildcardType;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReferenceParameterList;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.impl.source.PsiClassReferenceType;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class JavaBackendFoldings {

  // todo BACKEND-ONLY: whole method requires resolve (DumbService guard, PropertyUtilBase getter/setter checks)
  static boolean isSimplePropertyAccessor(@NotNull PsiMethod method) {
    if (DumbService.isDumb(method.getProject())) return false;

    PsiCodeBlock body = method.getBody();
    if (body == null || body.getLBrace() == null || body.getRBrace() == null) return false;
    PsiStatement[] statements = body.getStatements();
    if (statements.length == 0) return false;

    PsiStatement statement = statements[0];
    if (PropertyUtilBase.isSimplePropertyGetter(method)) {
      if (statement instanceof PsiReturnStatement) {
        return ((PsiReturnStatement)statement).getReturnValue() instanceof PsiReferenceExpression;
      }
      return false;
    }

    // builder-style setter?
    if (statements.length > 1 && !(statements[1] instanceof PsiReturnStatement)) return false;

    // any setter?
    if (statement instanceof PsiExpressionStatement) {
      PsiExpression expr = ((PsiExpressionStatement)statement).getExpression();
      if (expr instanceof PsiAssignmentExpression) {
        PsiExpression lhs = ((PsiAssignmentExpression)expr).getLExpression();
        PsiExpression rhs = ((PsiAssignmentExpression)expr).getRExpression();
        return lhs instanceof PsiReferenceExpression &&
               rhs instanceof PsiReferenceExpression &&
               !((PsiReferenceExpression)rhs).isQualified() &&
               PropertyUtilBase.isSimplePropertySetter(method); // last check because it can perform long return type resolve
      }
    }
    return false;
  }

  // todo BACKEND-ONLY: resolvesCorrectly() calls multiResolve (guarded by !quick)
  static void addMethodGenericParametersFolding(@NotNull List<? super FoldingDescriptor> list,
                                                        @NotNull PsiMethodCallExpression expression,
                                                        @NotNull Document document,
                                                        boolean quick) {
    final PsiReferenceExpression methodExpression = expression.getMethodExpression();
    final PsiReferenceParameterList parameterList = methodExpression.getParameterList();
    if (parameterList == null || parameterList.getTextLength() <= 5) {
      return;
    }

    PsiMethodCallExpression element = expression;
    while (true) {
      if (!quick && !resolvesCorrectly(element.getMethodExpression())) return;
      final PsiElement parent = element.getParent();
      if (!(parent instanceof PsiExpressionList) || !(parent.getParent() instanceof PsiMethodCallExpression)) break;
      element = (PsiMethodCallExpression)parent.getParent();
    }

    addTypeParametersFolding(list, document, parameterList, 3, quick);
  }

  // todo BACKEND-ONLY: type inference for 'var' — expression.getType() requires resolve (guarded by quick)
  static void addLocalVariableTypeFolding(@NotNull List<? super FoldingDescriptor> list,
                                                  @NotNull PsiVariable expression,
                                                  boolean quick) {
    if (quick) return; // presentable text may require resolve
    PsiTypeElement typeElement = expression.getTypeElement();
    if (typeElement == null) return;
    if (!typeElement.isInferredType()) return;
    PsiType type = expression.getType();
    if (type instanceof PsiCapturedWildcardType || type.equals(PsiTypes.nullType())) return;
    String presentableText = type.getPresentableText();
    if (presentableText.length() > 25) return;
    list.add(new FoldingDescriptor(typeElement.getNode(), typeElement.getTextRange(), null, presentableText, true, Collections.emptySet()));
  }

  // todo BACKEND-ONLY: multiResolve
  private static boolean resolvesCorrectly(@NotNull PsiReferenceExpression expression) {
    for (final JavaResolveResult result : expression.multiResolve(true)) {
      if (!result.isValidResult()) {
        return false;
      }
    }
    return true;
  }

  // todo BACKEND-ONLY: type resolution for generic parameters (variable type, class type parameters, superclass resolve)
  static void addGenericParametersFolding(@NotNull List<? super FoldingDescriptor> list,
                                                  @NotNull PsiNewExpression expression,
                                                  @NotNull Document document,
                                                  boolean quick) {
    final PsiElement parent = expression.getParent();
    if (!(parent instanceof PsiVariable)) {
      return;
    }

    final PsiType declType = ((PsiVariable)parent).getType();
    if (!(declType instanceof PsiClassReferenceType)) {
      return;
    }

    final PsiType[] parameters = ((PsiClassType)declType).getParameters();
    if (parameters.length == 0) {
      return;
    }

    PsiJavaCodeReferenceElement classReference = expression.getClassReference();
    if (classReference == null) {
      final PsiAnonymousClass anonymousClass = expression.getAnonymousClass();
      if (anonymousClass != null) {
        classReference = anonymousClass.getBaseClassReference();

        if (quick || ClosureFolding.seemsLikeLambda(anonymousClass.getSuperClass(), anonymousClass)) {
          return;
        }
      }
    }

    if (classReference != null) {
      final PsiReferenceParameterList parameterList = classReference.getParameterList();
      if (parameterList != null) {
        if (quick) {
          final PsiJavaCodeReferenceElement declReference = ((PsiClassReferenceType)declType).getReference();
          final PsiReferenceParameterList declList = declReference.getParameterList();
          if (declList == null || !parameterList.getText().equals(declList.getText())) {
            return;
          }
        }
        else if (!Arrays.equals(parameterList.getTypeArguments(), parameters)) {
          return;
        }

        addTypeParametersFolding(list, document, parameterList, 5, quick);
      }
    }
  }

  // todo BACKEND-ONLY (non-quick path): resolveClassInType on type arguments
  private static void addTypeParametersFolding(@NotNull List<? super FoldingDescriptor> list,
                                               @NotNull Document document,
                                               @NotNull PsiReferenceParameterList parameterList,
                                               int ifLongerThan,
                                               boolean quick) {
    if (!quick) {
      for (final PsiType type : parameterList.getTypeArguments()) {
        if (!type.isValid()) {
          return;
        }
        if (type instanceof PsiClassType || type instanceof PsiArrayType) {
          if (PsiUtil.resolveClassInType(type) == null) {
            return;
          }
        }
      }
    }

    final String text = parameterList.getText();
    if (text.startsWith("<") && text.endsWith(">") && text.length() > ifLongerThan) {
      final TextRange range = parameterList.getTextRange();
      JavaFoldingUtil.addFoldRegion(list, parameterList, document, true, range, "<~>",
                                    JavaCodeFoldingSettings.getInstance().isCollapseConstructorGenericParameters());
    }
  }

  //todo backend-only
  static boolean isCollapseMethodByDefault(@NotNull PsiMethod element) {
    JavaCodeFoldingSettings settings = JavaCodeFoldingSettings.getInstance();
    if (!settings.isCollapseAccessors() && !settings.isCollapseMethods()) {
      return false;
    }

    if (isSimplePropertyAccessor(element)) {
      return settings.isCollapseAccessors();
    }
    return settings.isCollapseMethods();
  }
}
