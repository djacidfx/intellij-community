// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.postfix.templates.editable;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.template.CustomTemplateCallback;
import com.intellij.codeInsight.template.Template;
import com.intellij.codeInsight.template.TemplateManager;
import com.intellij.codeInsight.template.impl.TemplateImpl;
import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TextExpression;
import com.intellij.codeInsight.template.postfix.templates.PostfixLiveTemplate;
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplate;
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplateProvider;
import com.intellij.codeInsight.template.postfix.templates.PostfixTemplatesUtils;
import com.intellij.codeInsight.unwrap.ScopeHighlighter;
import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModCommand;
import com.intellij.modcommand.ModCommandAction;
import com.intellij.modcommand.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pass;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.PostprocessReformattingAspect;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.IntroduceTargetChooser;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Objects;

/**
 * Base class for editable templates.
 * Template data is backed by live template.
 * It supports selecting the expression a template is applied to.
 *
 * @see EditablePostfixTemplateWithMultipleExpressions
 * @see <a href="https://plugins.jetbrains.com/docs/intellij/advanced-postfix-templates.html">Advanced Postfix Templates (IntelliJ Platform Docs)</a>
 */
public abstract class EditablePostfixTemplate extends PostfixTemplate {
  private final @NotNull TemplateImpl myLiveTemplate;

  public EditablePostfixTemplate(@NotNull String templateId,
                                 @NotNull String templateName,
                                 @NotNull TemplateImpl liveTemplate,
                                 @NotNull String example,
                                 @NotNull PostfixTemplateProvider provider) {
    this(templateId, templateName, "." + templateName, liveTemplate, example, provider);
  }

  public EditablePostfixTemplate(@NotNull String templateId,
                                 @NotNull String templateName,
                                 @NotNull String templateKey,
                                 @NotNull TemplateImpl liveTemplate,
                                 @NotNull String example,
                                 @NotNull PostfixTemplateProvider provider) {
    super(templateId, templateName, templateKey, example, provider);
    assert StringUtil.isNotEmpty(liveTemplate.getKey());
    myLiveTemplate = liveTemplate;
  }

  public @NotNull TemplateImpl getLiveTemplate() {
    return myLiveTemplate;
  }

  @Override
  public final void expand(@NotNull PsiElement context, final @NotNull Editor editor) {
    List<PsiElement> expressions = getExpressions(context, editor.getDocument(), editor.getCaretModel().getOffset());

    if (expressions.isEmpty()) {
      PostfixTemplatesUtils.showErrorHint(context.getProject(), editor);
      return;
    }

    if (expressions.size() == 1) {
      prepareAndExpandForChooseExpression(expressions.getFirst(), editor);
      return;
    }

    if (ApplicationManager.getApplication().isUnitTestMode()) {
      PsiElement item = ContainerUtil.getFirstItem(expressions);
      assert item != null;
      prepareAndExpandForChooseExpression(item, editor);
      return;
    }

    IntroduceTargetChooser.showChooser(
      editor, expressions,
      new Pass<>() {
        @Override
        public void pass(final @NotNull PsiElement e) {
          prepareAndExpandForChooseExpression(e, editor);
        }
      },
      getElementRenderer(),
      CodeInsightBundle.message("dialog.title.expressions"), 0, ScopeHighlighter.NATURAL_RANGER
    );
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof EditablePostfixTemplate template)) return false;
    if (!super.equals(o)) return false;
    return Objects.equals(myLiveTemplate, template.myLiveTemplate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(getKey(), myLiveTemplate);
  }

  protected abstract @Unmodifiable List<PsiElement> getExpressions(@NotNull PsiElement context, @NotNull Document document, int offset);

  @Override
  public boolean isApplicable(@NotNull PsiElement context, @NotNull Document copyDocument, int newOffset) {
    return !getExpressions(context, copyDocument, newOffset).isEmpty();
  }

  @ApiStatus.Experimental
  @Override
  public @NotNull ModCommand expandMod(@NotNull ActionContext actionContext,
                                       @NotNull PostfixTemplateProvider provider,
                                       @NotNull TextRange keyRange) {
    Project project = actionContext.project();
    List<PsiElement> virtualExpressions = PostprocessReformattingAspect.getInstance(project).disablePostprocessFormattingInside(() -> {
      PsiFile copyFile = (PsiFile)actionContext.file().copy();
      Document copyDocument = copyFile.getFileDocument();
      int startOffset = keyRange.getStartOffset();
      startOffset = PostfixLiveTemplate.positiveOffset(startOffset);
      copyDocument.deleteString(startOffset, keyRange.getEndOffset());
      PsiDocumentManager.getInstance(project).commitDocument(copyDocument);
      provider.preCheckModCommand(copyFile, startOffset);
      PsiDocumentManager.getInstance(project).commitDocument(copyDocument);
      PsiElement context = CustomTemplateCallback.getContext(copyFile, PostfixLiveTemplate.positiveOffset(startOffset));
      return getExpressions(context, context.getContainingFile().getFileDocument(), startOffset);
    });
    if (virtualExpressions.isEmpty()) {
      return ModCommand.nop();
    }

    if (virtualExpressions.size() == 1) {
      return createModCommand(actionContext, keyRange, virtualExpressions.getFirst(), provider);
    }

    List<ModCommandAction> actions = ContainerUtil.mapNotNull(
      virtualExpressions,
      expr -> buildExpandModAction(expr, getElementRenderer().fun(expr), new TextRange(keyRange.getStartOffset(), keyRange.getStartOffset()), provider));
    if (actions.isEmpty()) {
      return ModCommand.nop();
    }
    return ModCommand.chooseAction(CodeInsightBundle.message("dialog.title.expressions"), actions);
  }

  @SuppressWarnings("HardCodedStringLiteral") // expression text is used as chooser item title
  private @NotNull ModCommandAction buildExpandModAction(@NotNull PsiElement virtualExpression,
                                                         @NotNull String title,
                                                         @NotNull TextRange key,
                                                         @NotNull PostfixTemplateProvider provider) {

    return new ModCommandAction() {
      @Override
      public @NotNull Presentation getPresentation(@NotNull ActionContext ctx) {
        return Presentation.of(title).withHighlighting(virtualExpression.getTextRange());
      }

      @Override
      public @NotNull ModCommand perform(@NotNull ActionContext ctx) {
        return createModCommand(ctx.withSelection(new TextRange(key.getStartOffset(), key.getStartOffset())), key, virtualExpression, provider);
      }

      @Override
      public @NotNull String getFamilyName() {
        return title;
      }
    };
  }

  private @NotNull ModCommand createModCommand(@NotNull ActionContext ctx, @NotNull TextRange key, @NotNull PsiElement virtualExpression, @NotNull PostfixTemplateProvider provider) {
    return ModCommand.psiUpdate(ctx.withSelection(new TextRange(key.getStartOffset(), key.getStartOffset())).withOffset(key.getStartOffset()), document -> {
                                  document.deleteString(ctx.selection().getStartOffset(), ctx.selection().getEndOffset());
                                },
                                updater -> {
                                  updater.getDocument().deleteString(key.getStartOffset() - 1, ctx.selection().getStartOffset());
                                  PsiDocumentManager.getInstance(ctx.project()).commitDocument(updater.getDocument());
                                  provider.preCheckModCommand(updater.getPsiFile(), key.getStartOffset() - 1);
                                  String exprText = virtualExpression.getText();
                                  PsiElement expression = PsiTreeUtil.findSameElementInCopy(virtualExpression, updater.getPsiFile());
                                  TextRange rangeToRemove = getRangeToRemove(expression);
                                  TemplateImpl template = myLiveTemplate.copy();
                                  updater.getDocument().deleteString(rangeToRemove.getStartOffset(), rangeToRemove.getEndOffset());
                                  template.addVariable("EXPR", new TextExpression(exprText), false);
                                  addTemplateVariables(expression, template);
                                  TemplateManagerImpl.updateTemplate(template, updater);
                                });
  }

  protected void addTemplateVariables(@NotNull PsiElement element, @NotNull Template template) {
  }

  /**
   * @param element element to which the template was applied
   * @return an element to remove before inserting the template
   */
  protected @NotNull PsiElement getElementToRemove(@NotNull PsiElement element) {
    return element;
  }

  /**
   * Default implementation delegates to {@link #getElementToRemove(PsiElement)} and takes the text range of the resulting element.
   * Override it if it's desired to remove only a part of {@code PsiElement}'s range.
   *
   * @param element element to which the template was applied
   * @return a range to remove before inserting the template
   */
  protected @NotNull TextRange getRangeToRemove(@NotNull PsiElement element) {
    return getElementToRemove(element).getTextRange();
  }

  protected @NotNull Function<PsiElement, String> getElementRenderer() {
    return element -> element.getText();
  }

  @Override
  public @NotNull PostfixTemplateProvider getProvider() {
    PostfixTemplateProvider provider = super.getProvider();
    assert provider != null;
    return provider;
  }

  private void prepareAndExpandForChooseExpression(@NotNull PsiElement element, @NotNull Editor editor) {
    ApplicationManager.getApplication().runWriteAction(
      () -> CommandProcessor.getInstance().executeCommand(
        element.getProject(), () -> expandForChooseExpression(element, editor),
        CodeInsightBundle.message("command.expand.postfix.template"),
        PostfixLiveTemplate.POSTFIX_TEMPLATE_ID));
  }

  private void expandForChooseExpression(@NotNull PsiElement element, @NotNull Editor editor) {
    Project project = element.getProject();
    Document document = editor.getDocument();
    TextRange range = getRangeToRemove(element);
    document.deleteString(range.getStartOffset(), range.getEndOffset());
    TemplateManager manager = TemplateManager.getInstance(project);

    TemplateImpl template = myLiveTemplate.copy();
    template.addVariable("EXPR", new TextExpression(element.getText()), false);
    addTemplateVariables(element, template);
    manager.startTemplate(editor, template);
  }
}
