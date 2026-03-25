// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeInsight.template.postfix.templates;

public class ModVarPostfixTemplateTest extends VarPostfixTemplateTest {
  @Override
  protected boolean useModCommandTemplates() {
    return true;
  }

  @Override
  public void testStreamStep() {
    doTest();
  }
}
