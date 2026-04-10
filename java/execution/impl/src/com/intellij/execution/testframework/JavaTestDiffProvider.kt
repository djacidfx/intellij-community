// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.execution.testframework

import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.util.asSafely

class JavaTestDiffProvider : JvmTestDiffProvider() {
  override fun updateExpected(element: PsiElement, actual: String) {
    val literalExpr = element.asSafely<PsiLiteralExpression>()
    if (literalExpr == null || !literalExpr.isTextBlock) {
      super.updateExpected(element, actual)
      return
    }

    val text = literalExpr.text
    val closingStart = text.lastIndexOf("\"\"\"")
    if (closingStart < 0 || !text.contains('\n')) {
      super.updateExpected(element, actual)
      return
    }

    val lineStart = text.lastIndexOf('\n', closingStart) + 1
    val indent = text.substring(lineStart, closingStart).takeWhile { it == ' ' || it == '\t' } // get all indent from the last line
    val result = "\"\"\"\n" + actual.split('\n').joinToString("\n") { indent + it } + "\"\"\"" // соедините каждую строку отступом
    val expr = JavaPsiFacade.getElementFactory(literalExpr.project)
      .createExpressionFromText(result, literalExpr.parent)
    literalExpr.replace(expr)
  }

  override fun getExpectedValue(element: PsiElement): String {
    val literalValue = (element as? PsiLiteralExpression)?.value
    if (literalValue is String) return literalValue
    return ElementManipulators.getValueText(element)
  }
}
