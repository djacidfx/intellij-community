// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.folding.impl;

import com.intellij.codeInsight.folding.JavaCodeFoldingSettings;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.CustomFoldingBuilder;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.FoldingGroup;
import com.intellij.openapi.progress.ProgressIndicatorProvider;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaRecursiveElementWalkingVisitor;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiJavaModule;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiRecordHeader;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.impl.source.tree.JavaElementType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public abstract class JavaFoldingBuilderBase extends CustomFoldingBuilder implements DumbAware {
  private static final Logger LOG = Logger.getInstance(JavaFoldingBuilderBase.class);

  //todo backend
  protected abstract boolean shouldShowExplicitLambdaType(@NotNull PsiAnonymousClass anonymousClass, @NotNull PsiNewExpression expression);

  @Override
  protected void buildLanguageFoldRegions(@NotNull List<FoldingDescriptor> descriptors,
                                          @NotNull PsiElement root,
                                          @NotNull Document document,
                                          boolean quick) {
    if (!(root instanceof PsiJavaFile)) return;
    PsiJavaFile file = (PsiJavaFile)root;

    JavaFoldingUtil.addFoldsForImports(descriptors, file);

    PsiJavaModule module = file.getModuleDeclaration();
    if (module != null) {
      JavaFoldingUtil.addFoldsForModule(descriptors, module, document);
    }

    PsiClass[] classes = file.getClasses();
    for (PsiClass aClass : classes) {
      ProgressManager.checkCanceled();
      ProgressIndicatorProvider.checkCanceled();
      addFoldsForClass(descriptors, aClass, document, quick);
    }

    JavaFoldingUtil.addFoldsForFileHeader(descriptors, file, document);
    JavaFoldingUtil.addCommentsToFold(descriptors, root, document);
  }

  private void addFoldsForClass(@NotNull List<? super FoldingDescriptor> list,
                                @NotNull PsiClass aClass,
                                @NotNull Document document,
                                boolean quick) {
    PsiElement parent = aClass.getParent();
    if (!(parent instanceof PsiJavaFile) || ((PsiJavaFile)parent).getClasses().length > 1) {
      JavaFoldingUtil.addToFold(list, aClass, document, true, JavaFoldingUtil.getCodeBlockPlaceholder(null), JavaFoldingUtil.classRange(aClass),
                !(parent instanceof PsiFile) && JavaCodeFoldingSettings.getInstance().isCollapseInnerClasses());
    }

    JavaFoldingUtil.addAnnotationsToFold(list, aClass.getModifierList(), document);

    for (PsiElement child = aClass.getFirstChild(); child != null; child = child.getNextSibling()) {
      ProgressIndicatorProvider.checkCanceled();

      if (child instanceof PsiMethod) {
        PsiMethod method = (PsiMethod)child;

        addFoldsForMethod(list, method, document, quick);
      }
      else if (child instanceof PsiField) {
        PsiField field = (PsiField)child;

        JavaFoldingUtil.addCommentsToFold(list, field, document);

        JavaFoldingUtil.addAnnotationsToFold(list, field.getModifierList(), document);

        PsiExpression initializer = field.getInitializer();
        if (initializer != null) {
          //todo can be BACKEND-ONLY
          addCodeBlockFolds(list, initializer, document, quick);
        }
        else if (field instanceof PsiEnumConstant) {
          //todo can be BACKEND-ONLY
          addCodeBlockFolds(list, field, document, quick);
        }
      }
      else if (child instanceof PsiClassInitializer) {
        PsiClassInitializer initializer = (PsiClassInitializer)child;
        JavaFoldingUtil.addToFold(list, child, document, true, JavaFoldingUtil.getCodeBlockPlaceholder(initializer.getBody()), initializer.getBody().getTextRange(),
                  JavaCodeFoldingSettings.getInstance().isCollapseMethods());
        //todo can be BACKEND-ONLY
        addCodeBlockFolds(list, child, document, quick);
      }
      else if (child instanceof PsiClass) {
        addFoldsForClass(list, (PsiClass)child, document, quick);
      }
      else if (child instanceof PsiComment) {
        JavaFoldingUtil.addCommentToFold(list, (PsiComment)child, document);
      }
      else if (child instanceof PsiRecordHeader) {
        JavaFoldingUtil.addToFold(list, child, document, false, "(...)", child.getTextRange(), false);
      }
    }
  }

  private void addFoldsForMethod(@NotNull List<? super FoldingDescriptor> list,
                                 @NotNull PsiMethod method,
                                 @NotNull Document document,
                                 boolean quick) {
    //todo better BACKEND-ONLY
    boolean oneLiner = addOneLineMethodFolding(list, method);
    if (!oneLiner) {
      //todo BACKEND-ONLY
      boolean collapseMethodByDefault = JavaBackendFoldings.isCollapseMethodByDefault(method);
      JavaFoldingUtil.addToFold(list, method, document, true, JavaFoldingUtil.getCodeBlockPlaceholder(method.getBody()), JavaFoldingUtil.methodRange(method),
                                collapseMethodByDefault);
    }

    JavaFoldingUtil.addAnnotationsToFold(list, method.getModifierList(), document);

    JavaFoldingUtil.addCommentsToFold(list, method, document);

    for (PsiParameter parameter : method.getParameterList().getParameters()) {
      JavaFoldingUtil.addAnnotationsToFold(list, parameter.getModifierList(), document);
    }

    PsiCodeBlock body = method.getBody();
    if (body != null) {
      //todo can be BACKEND-ONLY
      addCodeBlockFolds(list, body, document, quick);
    }
  }

  //todo backend only
  private boolean addOneLineMethodFolding(@NotNull List<? super FoldingDescriptor> list, @NotNull PsiMethod method) {
    boolean collapseOneLineMethods = JavaCodeFoldingSettings.getInstance().isCollapseOneLineMethods();
    if (!collapseOneLineMethods) {
      return false;
    }

    Document document = method.getContainingFile().getViewProvider().getDocument();
    PsiCodeBlock body = method.getBody();
    PsiIdentifier nameIdentifier = method.getNameIdentifier();
    if (body == null || document == null || nameIdentifier == null) {
      return false;
    }
    TextRange parameterListTextRange = method.getParameterList().getTextRange();
    if (parameterListTextRange == null || document.getLineNumber(nameIdentifier.getTextRange().getStartOffset()) !=
                                          document.getLineNumber(parameterListTextRange.getEndOffset())) {
      return false;
    }

    PsiJavaToken lBrace = body.getLBrace();
    PsiJavaToken rBrace = body.getRBrace();
    PsiStatement[] statements = body.getStatements();
    if (lBrace == null || rBrace == null || statements.length != 1) {
      return false;
    }

    PsiStatement statement = statements[0];
    if (statement.textContains('\n')) {
      return false;
    }

    if (!JavaFoldingUtil.areOnAdjacentLines(lBrace, statement, document) || !JavaFoldingUtil.areOnAdjacentLines(statement, rBrace, document)) {
      //the user might intend to type at an empty line
      return false;
    }

    int leftStart = parameterListTextRange.getEndOffset();
    int bodyStart = body.getTextRange().getStartOffset();
    if (bodyStart > leftStart && !StringUtil.isEmptyOrSpaces(document.getCharsSequence().subSequence(leftStart + 1, bodyStart))) {
      return false;
    }

    int leftEnd = statement.getTextRange().getStartOffset();
    int rightStart = statement.getTextRange().getEndOffset();
    int rightEnd = body.getTextRange().getEndOffset();
    if (leftEnd <= leftStart + 1 || rightEnd <= rightStart + 1) {
      return false;
    }

    String leftText = " { ";
    String rightText = " }";
    if (!fitsRightMargin(method, document, leftStart, rightEnd, rightStart - leftEnd + leftText.length() + rightText.length())) {
      return false;
    }

    FoldingGroup group = FoldingGroup.newGroup("one-liner");
    list.add(new FoldingDescriptor(lBrace.getNode(), new TextRange(leftStart, leftEnd), group, leftText, true, Collections.emptySet()));
    list.add(new FoldingDescriptor(rBrace.getNode(), new TextRange(rightStart, rightEnd), group, rightText, true, Collections.emptySet()));
    return true;
  }

  @Override
  protected String getLanguagePlaceholderText(@NotNull ASTNode node, @NotNull TextRange range) {
    return null;
  }

  @Override
  protected boolean isRegionCollapsedByDefault(@NotNull ASTNode node) {
    LOG.error("Unknown element:" + node);
    return false;
  }

  // todo can be BACKEND-ONLY
  private void addCodeBlockFolds(final @NotNull List<? super FoldingDescriptor> list, @NotNull PsiElement scope,
                                 final @NotNull Document document,
                                 final boolean quick) {
    final boolean dumb = DumbService.isDumb(scope.getProject());
    scope.accept(new JavaRecursiveElementWalkingVisitor() {
      @Override
      // todo BACKEND-ONLY: addClosureFolding requires resolve (base class type, functional interface check)
      public void visitClass(@NotNull PsiClass aClass) {
        if (dumb || !addClosureFolding(aClass, document, list, quick)) {
          JavaFoldingUtil.addToFold(list, aClass, document, true, JavaFoldingUtil.getCodeBlockPlaceholder(null), JavaFoldingUtil.classRange(aClass),
                    JavaCodeFoldingSettings.getInstance().isCollapseInnerClasses());
          addFoldsForClass(list, aClass, document, quick);
        }
      }

      // todo BACKEND-ONLY: addLocalVariableTypeFolding infers 'var' type via resolve
      @Override
      public void visitVariable(@NotNull PsiVariable variable) {
        if (!dumb && JavaCodeFoldingSettings.getInstance().isReplaceVarWithInferredType()) {
          JavaBackendFoldings.addLocalVariableTypeFolding(list, variable, quick);
        }

        super.visitVariable(variable);
      }

      // todo BACKEND-ONLY: addMethodGenericParametersFolding calls multiResolve
      @Override
      public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
        if (!dumb) {
          JavaBackendFoldings.addMethodGenericParametersFolding(list, expression, document, quick);
        }

        super.visitMethodCallExpression(expression);
      }

      // todo BACKEND-ONLY: addGenericParametersFolding resolves variable type and type parameters
      @Override
      public void visitNewExpression(@NotNull PsiNewExpression expression) {
        if (!dumb) {
          JavaBackendFoldings.addGenericParametersFolding(list, expression, document, quick);
        }

        super.visitNewExpression(expression);
      }

      @Override
      public void visitLambdaExpression(@NotNull PsiLambdaExpression expression) {
        PsiElement body = expression.getBody();
        if (body instanceof PsiCodeBlock) {
          JavaFoldingUtil.addToFold(list, expression, document, true, JavaFoldingUtil.getCodeBlockPlaceholder(expression.getBody()), JavaFoldingUtil.lambdaRange(expression),
                    JavaCodeFoldingSettings.getInstance().isCollapseAnonymousClasses());
        }
        super.visitLambdaExpression(expression);
      }

      @Override
      public void visitCodeBlock(@NotNull PsiCodeBlock block) {
        if (Registry.is("java.folding.icons.for.control.flow", true) && block.getStatementCount() > 0) {
          JavaFoldingUtil.addToFold(list, block, document, false, JavaFoldingUtil.getCodeBlockPlaceholder(block), block.getTextRange(), false);
        }
        super.visitCodeBlock(block);
      }

      @Override
      public void visitComment(@NotNull PsiComment comment) {
        JavaFoldingUtil.addCommentToFold(list, comment, document);
        super.visitComment(comment);
      }
    });
  }

  // todo BACKEND-ONLY: ClosureFolding.prepare resolves base class type, checks functional interface, exception types
  private boolean addClosureFolding(@NotNull PsiClass aClass,
                                    @NotNull Document document,
                                    @NotNull List<? super FoldingDescriptor> list,
                                    boolean quick) {
    if (!JavaCodeFoldingSettings.getInstance().isCollapseLambdas()) {
      return false;
    }

    if (aClass instanceof PsiAnonymousClass) {
      final PsiAnonymousClass anonymousClass = (PsiAnonymousClass)aClass;
      ClosureFolding closureFolding = ClosureFolding.prepare(anonymousClass, quick, this);
      List<FoldingDescriptor> descriptors = closureFolding == null ? null : closureFolding.process(document);
      if (descriptors != null) {
        list.addAll(descriptors);
        //todo can be BACKEND-ONLY
        addCodeBlockFolds(list, closureFolding.methodBody, document, quick);
        return true;
      }
    }
    return false;
  }

  protected @NotNull String rightArrow() {
    return "->";
  }

  boolean fitsRightMargin(@NotNull PsiElement element, @NotNull Document document, int foldingStart, int foldingEnd, int collapsedLength) {
    final int beforeLength = foldingStart - document.getLineStartOffset(document.getLineNumber(foldingStart));
    final int afterLength = document.getLineEndOffset(document.getLineNumber(foldingEnd)) - foldingEnd;
    return isBelowRightMargin(element.getContainingFile(), beforeLength + collapsedLength + afterLength);
  }

  protected abstract boolean isBelowRightMargin(@NotNull PsiFile file, final int lineLength);

  @Override
  protected boolean isCustomFoldingCandidate(@NotNull ASTNode node) {
    return node.getElementType() == JavaTokenType.END_OF_LINE_COMMENT;
  }

  @Override
  protected boolean isCustomFoldingRoot(@NotNull ASTNode node) {
    IElementType nodeType = node.getElementType();
    if (nodeType == JavaElementType.CLASS) {
      ASTNode parent = node.getTreeParent();
      return parent == null || parent.getElementType() != JavaElementType.CLASS;
    }
    return nodeType == JavaElementType.CODE_BLOCK;
  }
}