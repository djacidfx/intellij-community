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
import com.intellij.psi.JavaResolveResult;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiAssignmentExpression;
import com.intellij.psi.PsiCapturedWildcardType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassInitializer;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiExpressionList;
import com.intellij.psi.PsiExpressionStatement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiJavaModule;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiRecordHeader;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiReferenceParameterList;
import com.intellij.psi.PsiReturnStatement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypeElement;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.impl.source.PsiClassReferenceType;
import com.intellij.psi.impl.source.tree.JavaElementType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PropertyUtilBase;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.PlatformUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public abstract class JavaFoldingBuilderBase extends CustomFoldingBuilder implements DumbAware {
  private static final Logger LOG = Logger.getInstance(JavaFoldingBuilderBase.class);

  // todo BACKEND-ONLY: whole method requires resolve (DumbService guard, PropertyUtilBase getter/setter checks)
  private static boolean isSimplePropertyAccessor(@NotNull PsiMethod method) {
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
  private static void addMethodGenericParametersFolding(@NotNull List<? super FoldingDescriptor> list,
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
  private static void addLocalVariableTypeFolding(@NotNull List<? super FoldingDescriptor> list,
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
  private static void addGenericParametersFolding(@NotNull List<? super FoldingDescriptor> list,
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

  //todo backend
  protected abstract boolean shouldShowExplicitLambdaType(@NotNull PsiAnonymousClass anonymousClass, @NotNull PsiNewExpression expression);

  @Override
  protected void buildLanguageFoldRegions(@NotNull List<FoldingDescriptor> descriptors,
                                          @NotNull PsiElement root,
                                          @NotNull Document document,
                                          boolean quick) {
    if (!(root instanceof PsiJavaFile)) return;
    if (PlatformUtils.isJetBrainsClient()) return;
    PsiJavaFile file = (PsiJavaFile)root;
    Set<PsiElement> processedComments = new HashSet<>();

    JavaFoldingUtil.addFoldsForImports(descriptors, file);

    PsiJavaModule module = file.getModuleDeclaration();
    if (module != null) {
      JavaFoldingUtil.addFoldsForModule(descriptors, module, document, processedComments);
    }

    PsiClass[] classes = file.getClasses();
    for (PsiClass aClass : classes) {
      ProgressManager.checkCanceled();
      ProgressIndicatorProvider.checkCanceled();
      addFoldsForClass(descriptors, aClass, document, processedComments, quick);
    }

    JavaFoldingUtil.addFoldsForFileHeader(descriptors, file, document);
    JavaFoldingUtil.addCommentsToFold(descriptors, root, document, processedComments);
  }

  private void addFoldsForClass(@NotNull List<? super FoldingDescriptor> list,
                                @NotNull PsiClass aClass,
                                @NotNull Document document,
                                @NotNull Set<? super PsiElement> processedComments,
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

        addFoldsForMethod(list, method, document, quick, processedComments);
      }
      else if (child instanceof PsiField) {
        PsiField field = (PsiField)child;

        JavaFoldingUtil.addCommentsToFold(list, field, document, processedComments);

        JavaFoldingUtil.addAnnotationsToFold(list, field.getModifierList(), document);

        PsiExpression initializer = field.getInitializer();
        if (initializer != null) {
          //todo can be BACKEND-ONLY
          addCodeBlockFolds(list, initializer, processedComments, document, quick);
        }
        else if (field instanceof PsiEnumConstant) {
          //todo can be BACKEND-ONLY
          addCodeBlockFolds(list, field, processedComments, document, quick);
        }
      }
      else if (child instanceof PsiClassInitializer) {
        PsiClassInitializer initializer = (PsiClassInitializer)child;
        JavaFoldingUtil.addToFold(list, child, document, true, JavaFoldingUtil.getCodeBlockPlaceholder(initializer.getBody()), initializer.getBody().getTextRange(),
                  JavaCodeFoldingSettings.getInstance().isCollapseMethods());
        //todo can be BACKEND-ONLY
        addCodeBlockFolds(list, child, processedComments, document, quick);
      }
      else if (child instanceof PsiClass) {
        addFoldsForClass(list, (PsiClass)child, document, processedComments, quick);
      }
      else if (child instanceof PsiComment) {
        JavaFoldingUtil.addCommentToFold(list, (PsiComment)child, document, processedComments);
      }
      else if (child instanceof PsiRecordHeader) {
        JavaFoldingUtil.addToFold(list, child, document, false, "(...)", child.getTextRange(), false);
      }
    }
  }

  private void addFoldsForMethod(@NotNull List<? super FoldingDescriptor> list,
                                 @NotNull PsiMethod method,
                                 @NotNull Document document,
                                 boolean quick,
                                 @NotNull Set<? super PsiElement> processedComments) {
    boolean oneLiner = addOneLineMethodFolding(list, method);
    if (!oneLiner) {
      //todo BACKEND-ONLY
      JavaFoldingUtil.addToFold(list, method, document, true, JavaFoldingUtil.getCodeBlockPlaceholder(method.getBody()), JavaFoldingUtil.methodRange(method),
                isCollapseMethodByDefault(method));
    }

    JavaFoldingUtil.addAnnotationsToFold(list, method.getModifierList(), document);

    JavaFoldingUtil.addCommentsToFold(list, method, document, processedComments);

    for (PsiParameter parameter : method.getParameterList().getParameters()) {
      JavaFoldingUtil.addAnnotationsToFold(list, parameter.getModifierList(), document);
    }

    PsiCodeBlock body = method.getBody();
    if (body != null) {
      //todo can be BACKEND-ONLY
      addCodeBlockFolds(list, body, processedComments, document, quick);
    }
  }

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

  //todo backend-only
  private static boolean isCollapseMethodByDefault(@NotNull PsiMethod element) {
    JavaCodeFoldingSettings settings = JavaCodeFoldingSettings.getInstance();
    if (!settings.isCollapseAccessors() && !settings.isCollapseMethods()) {
      return false;
    }

    if (isSimplePropertyAccessor(element)) {
      return settings.isCollapseAccessors();
    }
    return settings.isCollapseMethods();
  }
  // todo can be BACKEND-ONLY
  private void addCodeBlockFolds(final @NotNull List<? super FoldingDescriptor> list, @NotNull PsiElement scope,
                                 final @NotNull Set<? super PsiElement> processedComments,
                                 final @NotNull Document document,
                                 final boolean quick) {
    final boolean dumb = DumbService.isDumb(scope.getProject());
    scope.accept(new JavaRecursiveElementWalkingVisitor() {
      @Override
      // todo BACKEND-ONLY: addClosureFolding requires resolve (base class type, functional interface check)
      public void visitClass(@NotNull PsiClass aClass) {
        if (dumb || !addClosureFolding(aClass, document, list, processedComments, quick)) {
          JavaFoldingUtil.addToFold(list, aClass, document, true, JavaFoldingUtil.getCodeBlockPlaceholder(null), JavaFoldingUtil.classRange(aClass),
                    JavaCodeFoldingSettings.getInstance().isCollapseInnerClasses());
          addFoldsForClass(list, aClass, document, processedComments, quick);
        }
      }

      // todo BACKEND-ONLY: addLocalVariableTypeFolding infers 'var' type via resolve
      @Override
      public void visitVariable(@NotNull PsiVariable variable) {
        if (!dumb && JavaCodeFoldingSettings.getInstance().isReplaceVarWithInferredType()) {
          addLocalVariableTypeFolding(list, variable, quick);
        }

        super.visitVariable(variable);
      }

      // todo BACKEND-ONLY: addMethodGenericParametersFolding calls multiResolve
      @Override
      public void visitMethodCallExpression(@NotNull PsiMethodCallExpression expression) {
        if (!dumb) {
          addMethodGenericParametersFolding(list, expression, document, quick);
        }

        super.visitMethodCallExpression(expression);
      }

      // todo BACKEND-ONLY: addGenericParametersFolding resolves variable type and type parameters
      @Override
      public void visitNewExpression(@NotNull PsiNewExpression expression) {
        if (!dumb) {
          addGenericParametersFolding(list, expression, document, quick);
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
        JavaFoldingUtil.addCommentToFold(list, comment, document, processedComments);
        super.visitComment(comment);
      }
    });
  }

  // todo BACKEND-ONLY: ClosureFolding.prepare resolves base class type, checks functional interface, exception types
  private boolean addClosureFolding(@NotNull PsiClass aClass,
                                    @NotNull Document document,
                                    @NotNull List<? super FoldingDescriptor> list,
                                    @NotNull Set<? super PsiElement> processedComments,
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
        addCodeBlockFolds(list, closureFolding.methodBody, processedComments, document, quick);
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