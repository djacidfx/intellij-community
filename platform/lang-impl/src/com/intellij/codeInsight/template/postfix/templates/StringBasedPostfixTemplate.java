// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.postfix.templates;

import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.codeInsight.template.impl.TemplateImpl;
import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TextExpression;
import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class StringBasedPostfixTemplate extends PostfixTemplateWithExpressionSelector {

  public static final String EXPR = "expr";

  /**
   * @deprecated use {@link #StringBasedPostfixTemplate(String, String, PostfixTemplateExpressionSelector, PostfixTemplateProvider)}
   */
  @Deprecated
  public StringBasedPostfixTemplate(@NotNull String name,
                                    @NotNull String example,
                                    @NotNull PostfixTemplateExpressionSelector selector) {
    this(name, example, selector, null);
  }

  public StringBasedPostfixTemplate(@NotNull String name,
                                    @NotNull String example,
                                    @NotNull PostfixTemplateExpressionSelector selector,
                                    @Nullable PostfixTemplateProvider provider) {
    super(null, name, example, selector, provider);
  }

  public StringBasedPostfixTemplate(@NotNull String name,
                                    @NotNull String key,
                                    @NotNull String example,
                                    @NotNull PostfixTemplateExpressionSelector selector,
                                    @Nullable PostfixTemplateProvider provider) {
    super(null, name, key, example, selector, provider);
  }

  @Override
  public void expandForChooseExpression(@NotNull PsiElement expr, @NotNull Editor editor) {
    Project project = expr.getProject();
    Document document = editor.getDocument();
    PsiElement elementForRemoving = getElementToRemove(expr);
    document.deleteString(elementForRemoving.getTextRange().getStartOffset(), elementForRemoving.getTextRange().getEndOffset());
    TemplateManager manager = TemplateManager.getInstance(project);

    String templateString = getTemplateString(expr);
    if (templateString == null) {
      PostfixTemplatesUtils.showErrorHint(expr.getProject(), editor);
      return;
    }


    Template template = createTemplate(manager, templateString);
    template.addVariable(EXPR, new TextExpression(expr.getText()), false);
    setVariables(template, expr);
    manager.startTemplate(editor, template);
  }

  @Override
  public void expandModForChooseExpression(@NotNull ActionContext ctx,
                                           @NotNull ModPsiUpdater updater,
                                           @NotNull PsiElement elementInCopy) {
    String templateString = getTemplateString(elementInCopy);
    if (templateString == null) {
      return;
    }
    String exprText = elementInCopy.getText();
    PsiElement writableExpr = updater.getWritable(elementInCopy);
    PsiElement elementForRemoving = getElementToRemove(writableExpr);

    TemplateImpl template = (TemplateImpl)createTemplate(TemplateManager.getInstance(ctx.project()), templateString);
    template.addVariable(EXPR, new TextExpression(exprText), false);
    setVariables(template, writableExpr);

    TextRange range = elementForRemoving.getTextRange();
    updater.getDocument().deleteString(range.getStartOffset(), range.getEndOffset());

    TemplateManagerImpl.updateTemplate(template, updater);
  }

  public Template createTemplate(TemplateManager manager, String templateString) {
    Template template = manager.createTemplate("", "", templateString);
    template.setToReformat(shouldReformat());
    return template;
  }

  public void setVariables(@NotNull Template template, @NotNull PsiElement element) {
  }

  public abstract @Nullable String getTemplateString(@NotNull PsiElement element);

  protected boolean shouldReformat() {
    return true;
  }

  protected PsiElement getElementToRemove(PsiElement expr) {
    return expr.getParent();
  }
}
