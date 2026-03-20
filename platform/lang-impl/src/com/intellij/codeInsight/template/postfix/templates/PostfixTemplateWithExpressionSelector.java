// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.postfix.templates;

import com.intellij.codeInsight.CodeInsightBundle;
import com.intellij.codeInsight.template.CustomTemplateCallback;
import com.intellij.codeInsight.unwrap.ScopeHighlighter;
import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModCommand;
import com.intellij.modcommand.ModCommandAction;
import com.intellij.modcommand.ModPsiUpdater;
import com.intellij.modcommand.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsSafe;
import com.intellij.openapi.util.Pass;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.PostprocessReformattingAspect;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.IntroduceTargetChooser;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public abstract class PostfixTemplateWithExpressionSelector extends PostfixTemplate {
  private final @NotNull PostfixTemplateExpressionSelector mySelector;

  /**
   * @deprecated use {@link #PostfixTemplateWithExpressionSelector(String, String, String, String, PostfixTemplateExpressionSelector, PostfixTemplateProvider)}
   */
  @Deprecated(forRemoval = true)
  protected PostfixTemplateWithExpressionSelector(@NotNull @NlsSafe String name,
                                                  @NotNull @NlsSafe String key,
                                                  @NotNull @NlsSafe String example,
                                                  @NotNull PostfixTemplateExpressionSelector selector) {
    this(null, name, key, example, selector, null);
  }

  /**
   * @deprecated use {@link #PostfixTemplateWithExpressionSelector(String, String, String, PostfixTemplateExpressionSelector, PostfixTemplateProvider)}
   */
  @Deprecated(forRemoval = true)
  protected PostfixTemplateWithExpressionSelector(@NotNull @NlsSafe String name,
                                                  @NotNull @NlsSafe String example,
                                                  @NotNull PostfixTemplateExpressionSelector selector) {
    this(null, name, example, selector, null);
  }

  protected PostfixTemplateWithExpressionSelector(@Nullable @NonNls String id,
                                                  @NotNull @NlsSafe String name,
                                                  @NotNull @NlsSafe String example,
                                                  @NotNull PostfixTemplateExpressionSelector selector,
                                                  @Nullable PostfixTemplateProvider provider) {
    super(id, name, example, provider);
    mySelector = selector;
  }

  protected PostfixTemplateWithExpressionSelector(@Nullable @NonNls String id,
                                                  @NotNull @NlsSafe String name,
                                                  @NotNull @NlsSafe String key,
                                                  @NotNull @NlsSafe String example,
                                                  @NotNull PostfixTemplateExpressionSelector selector,
                                                  @Nullable PostfixTemplateProvider provider) {
    super(id, name, key, example, provider);
    mySelector = selector;
  }

  @Override
  public boolean isApplicable(@NotNull PsiElement context, @NotNull Document copyDocument, int newOffset) {
    return mySelector.hasExpression(context, copyDocument, newOffset);
  }

  @Override
  public final void expand(@NotNull PsiElement context, final @NotNull Editor editor) {
    List<PsiElement> expressions = mySelector.getExpressions(context,
                                                             editor.getDocument(),
                                                             editor.getCaretModel().getOffset());

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
      mySelector.getRenderer(),
      CodeInsightBundle.message("dialog.title.expressions"), 0, ScopeHighlighter.NATURAL_RANGER
    );
  }

  @Override
  public @NotNull ModCommand expandMod(@NotNull ActionContext actionContext,
                                       @NotNull PostfixTemplateProvider provider,
                                       @NotNull TextRange keyRange) {
    Project project = actionContext.project();
    List<PsiElement> expressions = PostprocessReformattingAspect.getInstance(project).disablePostprocessFormattingInside(() -> {
      PsiFile copyFile = (PsiFile)actionContext.file().copy();
      Document copyDocument = copyFile.getFileDocument();
      int startOffset = keyRange.getStartOffset();
      startOffset = PostfixLiveTemplate.positiveOffset(startOffset);
      copyDocument.deleteString(startOffset, keyRange.getEndOffset());
      PsiDocumentManager.getInstance(project).commitDocument(copyDocument);
      provider.preCheckModCommand(copyFile, startOffset);
      PsiDocumentManager.getInstance(project).commitDocument(copyDocument);
      PsiElement context = CustomTemplateCallback.getContext(copyFile, PostfixLiveTemplate.positiveOffset(startOffset));
      return mySelector.getExpressions(context, copyFile.getFileDocument(), startOffset);
    });
    if (expressions.isEmpty()) {
      return ModCommand.nop();
    }

    if (expressions.size() == 1) {
      return prepareAndExpandModForChooseExpression(actionContext, keyRange, expressions.getFirst(), provider);
    }

    List<ModCommandAction> actions = ContainerUtil.mapNotNull(
      expressions,
      expr -> {
        //noinspection HardCodedStringLiteral -- expression text is used as chooser item title
        String title = mySelector.getRenderer().fun(expr);
        return new ModCommandAction() {
          @Override
          public @NotNull Presentation getPresentation(@NotNull ActionContext ctx) {
            return Presentation.of(title).withHighlighting(expr.getTextRange());
          }

          @Override
          public @NotNull ModCommand perform(@NotNull ActionContext ctx) {
            return prepareAndExpandModForChooseExpression(actionContext
                                                            .withSelection(new TextRange(keyRange.getStartOffset(), keyRange.getStartOffset())),
                                                          new TextRange(keyRange.getStartOffset(), keyRange.getStartOffset()), expr, provider);
          }

          @Override
          public @NotNull String getFamilyName() {
            return title;
          }
        };
      }
    );
    if (actions.isEmpty()) return ModCommand.nop();
    return ModCommand.chooseAction(CodeInsightBundle.message("dialog.title.expressions"), actions);
  }

  private @NotNull ModCommand prepareAndExpandModForChooseExpression(@NotNull ActionContext ctx,
                                                                     @NotNull TextRange key,
                                                                     @NotNull PsiElement virtualExpression,
                                                                     @NotNull PostfixTemplateProvider provider) {
    TextRange selection = new TextRange(key.getStartOffset(), key.getStartOffset());
    ActionContext updatedContext = ctx.withSelection(selection).withOffset(key.getStartOffset());
    ModCommand command = ModCommand.psiUpdate(updatedContext, document -> {
                                                document.deleteString(ctx.selection().getStartOffset(), ctx.selection().getEndOffset());
                                              },
                                              updater -> {
                                                updater.getDocument().deleteString(key.getStartOffset() - 1, ctx.selection().getStartOffset());
                                                PsiDocumentManager.getInstance(ctx.project()).commitDocument(updater.getDocument());
                                                provider.preCheckModCommand(updater.getPsiFile(), key.getStartOffset() - 1);
                                                PsiElement elementInCopy =
                                                  PsiTreeUtil.findSameElementInCopy(virtualExpression, updater.getPsiFile());
                                                expandModForChooseExpression(updatedContext, updater, elementInCopy);
                                              });
    return command;
  }

  /**
   * Expands a "choose expression" postfix template modification in the provided context.
   *
   * @param ctx           the action context in which the postfix template is being expanded. This provides
   *                      information about the current file, project, caret offset, selection range, and context element.
   * @param updater       updater from ModCommand
   * @param elementInCopy the virtual PSI expression element to be expanded. It is from another copy of the original file.
   */
  @ApiStatus.Experimental
  public void expandModForChooseExpression(@NotNull ActionContext ctx,
                                                 @NotNull ModPsiUpdater updater,
                                                 @NotNull PsiElement elementInCopy) {
  }


  protected void prepareAndExpandForChooseExpression(@NotNull PsiElement expression, @NotNull Editor editor) {
    ApplicationManager.getApplication().runWriteAction(() -> CommandProcessor.getInstance()
      .executeCommand(expression.getProject(), () -> expandForChooseExpression(expression, editor),
                      CodeInsightBundle.message("command.expand.postfix.template"),
                      PostfixLiveTemplate.POSTFIX_TEMPLATE_ID));
  }

  @Override
  public boolean startInWriteAction() {
    return false;
  }

  protected abstract void expandForChooseExpression(@NotNull PsiElement expression, @NotNull Editor editor);
}
