// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.template.postfix.completion;

import com.intellij.codeInsight.template.CustomTemplateCallback;
import com.intellij.codeInsight.template.postfix.settings.PostfixTemplatesSettings;
import com.intellij.codeInsight.template.postfix.templates.*;
import com.intellij.icons.AllIcons;
import com.intellij.modcommand.ActionContext;
import com.intellij.modcommand.ModCommand;
import com.intellij.modcompletion.*;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.MarkupText;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiUtilCore;
import org.jetbrains.annotations.NotNull;

import java.util.Set;

/**
 * Provides postfix templates as {@link ModCompletionItem}s for use in ModCompletion.
 * Discovers applicable postfix templates at the current offset and emits a completion item
 * for each template that supports {@link PostfixTemplate#expandMod}.
 */
final class PostfixTemplateModCompletionItemProvider implements ModCompletionItemProvider {

  @Override
  public void provideItems(@NotNull CompletionContext context, @NotNull ModCompletionResult sink) {
    PostfixTemplatesSettings settings = PostfixTemplatesSettings.getInstance();
    if (!settings.isPostfixTemplatesEnabled()) return;

    PsiFile file = context.originalFile();
    int offset = context.offset();
    if (offset <= 0) return;

    var language = PsiUtilCore.getLanguageAtOffset(file, offset);

    for (PostfixTemplateProvider provider : LanguagePostfixTemplate.LANG_EP.allForLanguage(language)) {
      ProgressManager.checkCanceled();
      String key = PostfixLiveTemplate.computeTemplateKeyWithoutContextChecking(provider, file.getFileDocument().getCharsSequence(), offset);
      if (key == null) continue;

      int newOffset = offset - key.length();
      CharSequence fileContent = file.getFileDocument().getCharsSequence();
      StringBuilder contentWithoutKey = new StringBuilder();
      contentWithoutKey.append(fileContent.subSequence(0, newOffset));
      contentWithoutKey.append(fileContent.subSequence(offset, fileContent.length()));
      PsiFile copyFile = PostfixLiveTemplate.copyFile(file, contentWithoutKey);
      provider.preCheckModCommand(copyFile, newOffset);

      Document copyDocument = copyFile.getFileDocument();
      PsiElement copyContext = CustomTemplateCallback.getContext(copyFile, newOffset > 0 ? newOffset - 1 : newOffset);

      for (PostfixTemplate template : PostfixTemplatesUtils.getAvailableTemplates(provider)) {
        ProgressManager.checkCanceled();
        if (!isDumbEnough(template, copyContext)) continue;
        if (!template.isEnabled(provider)) continue;
        if (!template.isApplicable(copyContext, copyDocument, newOffset)) continue;
        if (!template.isApplicableForModCommand(copyContext, copyDocument, newOffset)) continue;
        sink.accept(new PostfixModCompletionItem(template));
      }
    }
  }

  private static boolean isDumbEnough(@NotNull PostfixTemplate template, @NotNull PsiElement context) {
    DumbService dumbService = DumbService.getInstance(context.getProject());
    return dumbService.isUsableInCurrentContext(template);
  }

  private static final class PostfixModCompletionItem implements ModCompletionItem {
    private final @NotNull PostfixTemplate myTemplate;

    private PostfixModCompletionItem(@NotNull PostfixTemplate template) {
      myTemplate = template;
    }

    @Override
    public @NotNull String mainLookupString() {
      return myTemplate.getKey();
    }

    @Override
    public @NotNull Set<String> additionalLookupStrings() {
      return Set.of();
    }

    @Override
    public @NotNull Object contextObject() {
      return myTemplate;
    }

    @Override
    public @NotNull ModCompletionItemPresentation presentation() {
      return new ModCompletionItemPresentation(MarkupText.plainText(myTemplate.getPresentableName()))
        .withMainIcon(() -> AllIcons.Nodes.Template)
        .withDetailText(MarkupText.plainText(myTemplate.getExample()));
    }

    @Override
    public @NotNull ModCommand perform(@NotNull ActionContext actionContext, @NotNull InsertionContext insertionContext) {
      ModCommand command = myTemplate.expandMod(actionContext);
      return command;
    }
  }

  @Override
  public boolean isEnabled() {
    return false;
  }
}
