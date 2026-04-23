package org.intellij.lang.regexp.intention;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.intellij.lang.annotations.Language;
import org.intellij.lang.regexp.RegExpFileType;

import javax.swing.tree.TreeNode;

/**
 * @author Bas Leijdekkers
 */
public final class ExplainRegExpIntentionTest extends BasePlatformTestCase {
  
  public void testSimple() {
    doTest("faceteous|hackneyed",
           """
             faceteous|hackneyed Alternation (https://www.regular-expressions.info/alternation.html) – matches 1 of 2 alternatives
             faceteous – matches these characters exactly
               f – matches the LATIN SMALL LETTER F character exactly
               a – matches the LATIN SMALL LETTER A character exactly
               c – matches the LATIN SMALL LETTER C character exactly
               e – matches the LATIN SMALL LETTER E character exactly
               t – matches the LATIN SMALL LETTER T character exactly
               e – matches the LATIN SMALL LETTER E character exactly
               o – matches the LATIN SMALL LETTER O character exactly
               u – matches the LATIN SMALL LETTER U character exactly
               s – matches the LATIN SMALL LETTER S character exactly
             hackneyed – matches these characters exactly
               h – matches the LATIN SMALL LETTER H character exactly
               a – matches the LATIN SMALL LETTER A character exactly
               c – matches the LATIN SMALL LETTER C character exactly
               k – matches the LATIN SMALL LETTER K character exactly
               n – matches the LATIN SMALL LETTER N character exactly
               e – matches the LATIN SMALL LETTER E character exactly
               y – matches the LATIN SMALL LETTER Y character exactly
               e – matches the LATIN SMALL LETTER E character exactly
               d – matches the LATIN SMALL LETTER D character exactly""");
  }

  private void doTest(@Language("RegExp") String regexp, String result) {
    PsiFile file = myFixture.configureByText(RegExpFileType.INSTANCE, regexp);
    String tree = toString(ExplainRegExpIntention.buildExplanationTree(file));
    assertEquals(result, tree);
  }

  private static String toString(TreeNode node) {
    return buildString(node, -2, new StringBuilder()).toString().trim();
  }

  private static StringBuilder buildString(TreeNode node, int depth, StringBuilder out) {
    if (depth > 0) out.append(StringUtil.repeat("  ", depth));
    out.append(node).append('\n');
    int count = node.getChildCount();
    depth++;
    for (int i = 0; i < count; i++) {
      buildString(node.getChildAt(i), depth, out);
    }
    return out;
  }
}
