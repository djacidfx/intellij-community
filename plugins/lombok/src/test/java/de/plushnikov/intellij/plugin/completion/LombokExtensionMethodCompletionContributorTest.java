// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package de.plushnikov.intellij.plugin.completion;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.template.impl.TemplateManagerImpl;
import com.intellij.codeInsight.template.impl.TemplateState;
import com.intellij.util.containers.ContainerUtil;
import de.plushnikov.intellij.plugin.AbstractLombokLightCodeInsightTestCase;
import de.plushnikov.intellij.plugin.psi.LombokExtensionMethod;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.intellij.codeInsight.completion.CompletionType.BASIC;
import static com.intellij.codeInsight.completion.CompletionType.SMART;

@NotNullByDefault
public class LombokExtensionMethodCompletionContributorTest extends AbstractLombokLightCodeInsightTestCase {
  @Override
  protected String getBasePath() {
    return super.getBasePath() + "/completion/extensionMethod/LombokExtensionMethodCompletionContributor";
  }

  public void testStringReceiverAndStringArgument() {
    doTestBothCompletionTypes("myExtensionMethod");
  }

  public void testObjectReceiverAndStringArgument() {
    doTestBothCompletionTypes("myExtensionMethod");
  }

  public void testStringReceiverAndObjectArgument() {
    doTestBothCompletionTypes();
  }

  public void testWildcardListReceiverAndListArgument() {
    doTestBothCompletionTypes("myExtensionMethod");
  }

  public void testStringReceiverAndWildcardWithUpperBoundStringArgument() {
    doTestBothCompletionTypes("myExtensionMethod");
  }

  public void testMoreThanTwoArguments() {
    doTestBothCompletionTypes("myExtensionMethod");
  }

  public void testLowerBoundedListReceiver() {
    doTestBothCompletionTypes();
  }

  public void testVarargReceiverAndArrayArgument() {
    doTestBothCompletionTypes("myExtensionMethod");
  }

  public void testVarargReceiverAndSingleElemArgument() {
    doTestBothCompletionTypes();
  }

  public void testMatchingExpressionType() {
    doTest(BASIC, "myIntegerMethod", "myStringMethod");
    doTest(SMART, "myIntegerMethod");
  }

  public void testGenericMethodMatchedByExpectedType() {
    doTest(BASIC, "myGenericMethod");
    doTest(SMART, "myGenericMethod");
  }

  public void testBoundedGenericMethodDoesNotMatchExpectedType() {
    doTest(BASIC, "myBoundedGenericMethod");
    doTest(SMART);
  }

  public void testWithPrefix() {
    doTest(BASIC, "myExtensionMethod", "myExtensionMethod2");
    doTest(SMART, "myExtensionMethod", "myExtensionMethod2");
  }

  public void testNonStaticExtensionMethod() {
    doTestBothCompletionTypes();
  }

  public void testNonPublicExtensionMethod() {
    doTestBothCompletionTypes();
  }

  public void testSameNameButDifferentSignatureThanInstanceMethod() {
    doTestBothCompletionTypes("myInstanceMethod");
  }

  public void testSameSignatureAsInstanceMethod() {
    extensionMethodIsNotSuggestedWhenReceiverSuperClassHasSameInstanceMethod(BASIC, "myInstanceMethod");
    extensionMethodIsNotSuggestedWhenReceiverSuperClassHasSameInstanceMethod(SMART, "myInstanceMethod");
  }

  public void testSameSignatureAsSuperClassInstanceMethod() {
    extensionMethodIsNotSuggestedWhenReceiverSuperClassHasSameInstanceMethod(BASIC, "myInstanceMethod");
    extensionMethodIsNotSuggestedWhenReceiverSuperClassHasSameInstanceMethod(SMART, "myInstanceMethod");
  }

  private void extensionMethodIsNotSuggestedWhenReceiverSuperClassHasSameInstanceMethod(CompletionType completionType, String methodName) {
    LookupElement[] lookupElements = configureAndGetAllCompletionSuggestions(completionType);
    List<String> allSuggestions = ContainerUtil.map(lookupElements, LookupElement::getLookupString);

    assertContainsElements(allSuggestions, methodName);
    assertEquals(Set.of(), filterLombokSuggestions(lookupElements));
  }

  private void doTestBothCompletionTypes(String... methodNames) {
    doTest(BASIC, methodNames);
    doTest(SMART, methodNames);
  }

  /// Verifies that all lombok generated suggestions in code completion are exactly equal to `methodNames`.
  private void doTest(CompletionType completionType, String... methodNames) {
    Set<String> actualSuggestions = configureAndGetLombokCompletionSuggestions(completionType);
    Set<String> expectedSuggestions = Set.of(methodNames);
    assertEquals(expectedSuggestions, actualSuggestions);
  }

  private Set<String> configureAndGetLombokCompletionSuggestions(CompletionType completionType) {
    LookupElement[] elements = configureAndGetAllCompletionSuggestions(completionType);
    return filterLombokSuggestions(elements);
  }

  private LookupElement[] configureAndGetAllCompletionSuggestions(CompletionType completionType) {
    myFixture.configureByFile(getTestName(false) + ".java");
    myFixture.complete(completionType);

    LookupElement[] lookupElements = myFixture.getLookupElements();
    assertNotNull("Element should not be autocompleted as there should be more than one element.", lookupElements);
    return lookupElements;
  }

  private static Set<String> filterLombokSuggestions(LookupElement[] lookupElements) {
    return Arrays.stream(lookupElements)
      .filter(l -> l.getObject() instanceof LombokExtensionMethod)
      .map(l -> l.getLookupString())
      .collect(Collectors.toSet());
  }
}
