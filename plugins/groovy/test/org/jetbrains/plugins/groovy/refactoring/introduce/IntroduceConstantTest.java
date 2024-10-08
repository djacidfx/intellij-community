// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.groovy.refactoring.introduce;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.testFramework.fixtures.JavaCodeInsightTestFixture;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.VisibilityUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.GrVariable;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.refactoring.introduce.constant.GrIntroduceConstantHandler;
import org.jetbrains.plugins.groovy.refactoring.introduce.constant.GrIntroduceConstantSettings;
import org.jetbrains.plugins.groovy.util.TestUtils;

/**
 * @author Maxim.Medvedev
 */
public class IntroduceConstantTest extends LightJavaCodeInsightFixtureTestCase {
  @Override
  protected String getBasePath() {
    return TestUtils.getTestDataPath() + "refactoring/introduceConstant/";
  }

  public void testSimple() {
    doTest();
  }

  public void testReplaceAllOccurrences() {
    doTest();
  }

  public void testEscalateVisibility() {
    doTest("Other", true, false, VisibilityUtil.ESCALATE_VISIBILITY);
  }

  public void testInsertInEnum() {
    doTest("Planet", false, false, PsiModifier.PROTECTED);
  }

  public void testInsertInInterface() {
    doTest("MyInterface", false, false, PsiModifier.PROTECTED);
  }

  public void testTupleDeclaration() {
    doTest("Test", true, false, PsiModifier.PUBLIC);
  }

  public void testStringPart() {
    doTest();
  }

  public void testAnonymousClass() {
    doTest();
  }

  public void testFieldWithClassName() {
    doTest();
  }

  public void testLocalVarRef() {
    doTest();
  }

  private void doTest() {
    doTest(null, true, true, PsiModifier.PUBLIC);
  }

  private void doTest(@Nullable String targetClassName, boolean replaceAllOccurrences, boolean useExplicitType, String modifier) {
    myFixture.configureByFile(getTestName(false) + ".groovy");

    final GrIntroduceConstantHandler handler = new GrIntroduceConstantHandler();
    final Editor editor = myFixture.getEditor();

    final GrExpression expression = findExpression(myFixture);
    final GrVariable variable = findVariable(myFixture);
    final StringPartInfo stringPart = findStringPart(myFixture);
    PsiElement[] scopes = handler.findPossibleScopes(expression, variable, stringPart, editor);
    final GrIntroduceContext context = handler.getContext(getProject(), editor, expression, variable, stringPart, scopes[0]);

    PsiClass targetClass =
      targetClassName == null ? GrIntroduceConstantHandler.findContainingClass(context) : myFixture.findClass(targetClassName);
    assertNotNull("target class is null", targetClass);

    PsiType type = getType(useExplicitType, expression, variable, stringPart);
    final GrIntroduceConstantSettings settings = new MockIntroduceConstantSettings(targetClass, replaceAllOccurrences, type, modifier);

    WriteCommandAction.runWriteCommandAction(getProject(), (Runnable) () -> handler.runRefactoring(context, settings));
    myFixture.checkResultByFile(getTestName(false) + "_after.groovy", true);
  }

  private static PsiType getType(boolean useExplicitType, GrExpression expression, GrVariable variable, StringPartInfo stringPart) {
    if (!useExplicitType) {
      return null;
    }

    return expression != null ? expression.getType() : variable != null ? variable.getType() : stringPart.getLiteral().getType();
  }

  @Nullable
  public static GrVariable findVariable(JavaCodeInsightTestFixture fixture) {
    final Editor editor = fixture.getEditor();
    final int start = editor.getSelectionModel().getSelectionStart();
    final int end = editor.getSelectionModel().getSelectionEnd();
    return GrIntroduceHandlerBase.findVariable(fixture.getFile(), start, end);
  }

  @Nullable
  public static GrExpression findExpression(JavaCodeInsightTestFixture fixture) {
    final Editor editor = fixture.getEditor();
    final int start = editor.getSelectionModel().getSelectionStart();
    final int end = editor.getSelectionModel().getSelectionEnd();
    return GrIntroduceHandlerBase.findExpression(fixture.getFile(), start, end);
  }

  @Nullable
  public static StringPartInfo findStringPart(JavaCodeInsightTestFixture fixture) {
    final Editor editor = fixture.getEditor();
    final int start = editor.getSelectionModel().getSelectionStart();
    final int end = editor.getSelectionModel().getSelectionEnd();
    return StringPartInfo.findStringPart(fixture.getFile(), start, end);
  }

  private static class MockIntroduceConstantSettings implements GrIntroduceConstantSettings {
    private final PsiClass myTargetClass;
    private final boolean myReplaceAllOccurrences;
    private final PsiType mySelectedType;
    private final String myModifier;

    private MockIntroduceConstantSettings(@NotNull PsiClass targetClass,
                                          boolean replaceAllOccurrences,
                                          @Nullable PsiType selectedType,
                                          String modifier) {
      myTargetClass = targetClass;
      myReplaceAllOccurrences = replaceAllOccurrences;
      mySelectedType = selectedType;
      myModifier = modifier;
    }

    @Override
    public String getVisibilityModifier() {
      return myModifier;
    }

    @Override
    public PsiClass getTargetClass() {
      return myTargetClass;
    }

    @Override
    public String getName() {
      return "CONST";
    }

    @Override
    public boolean replaceAllOccurrences() {
      return myReplaceAllOccurrences;
    }

    @Override
    public PsiType getSelectedType() {
      return mySelectedType;
    }
  }
}
