// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.lang.properties.codeInspection;

import com.intellij.codeInspection.ex.InspectionProfileImpl;
import com.intellij.modcommand.ModCommand;
import com.intellij.modcommand.ModCommandExecutor;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.profile.codeInspection.InspectionProfileManager;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class IncompletePropertyInspectionTest extends BasePlatformTestCase {
  private IncompletePropertyInspection myInspection;

  @Override
  protected void setUp() throws Exception {
    InspectionProfileImpl.INIT_INSPECTIONS = true;
    super.setUp();
    myInspection = new IncompletePropertyInspection();
    myFixture.enableInspections(myInspection);
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    InspectionProfileImpl.INIT_INSPECTIONS = false;
  }

  public void testHighlightsIncompleteProperty() {
    myFixture.addFileToProject("p_en.properties", "");
    myFixture.addFileToProject("p_fr.properties", "");
    myFixture.configureByText("p.properties", "<error descr=\"Property 'key' is missing translations in: en, fr\">key</error>=value\n");
    myFixture.checkHighlighting();
  }

  public void testNoHighlightWhenComplete() {
    myFixture.addFileToProject("p_en.properties", "key=value eng");
    myFixture.configureByText("p.properties", "key=value\n");
    myFixture.checkHighlighting();
  }

  public void testNoHighlightForSingleFile() {
    myFixture.configureByText("p.properties", "key=value\n");
    myFixture.checkHighlighting();
  }

  public void testIgnoredSuffixesNotHighlighted() {
    myFixture.addFileToProject("p_en.properties", "");
    myFixture.addFileToProject("p_fr.properties", "");
    myInspection.suffixes.addAll(List.of("en", "fr"));
    myFixture.configureByText("p.properties", "key=value\n");
    myFixture.checkHighlighting();
  }

  public void testPartiallyIgnoredSuffixes() {
    myFixture.addFileToProject("p_en.properties", "");
    myFixture.addFileToProject("p_fr.properties", "");
    myInspection.suffixes.add("en");
    myFixture.configureByText("p.properties", "<error descr=\"Property 'key' is missing translations in: fr\">key</error>=value\n");
    myFixture.checkHighlighting();
  }

  public void testQuickFixAddsIgnoredSuffixes() {
    myFixture.addFileToProject("p_en.properties", "");
    myFixture.addFileToProject("p_fr.properties", "");
    myFixture.configureByText("p.properties", "ke<caret>y=value\n");

    assertTrue(myInspection.suffixes.isEmpty());

    ModCommand command = ModCommand.updateInspectionOption(
      myFixture.getFile(), new IncompletePropertyInspection(), tool -> tool.suffixes.addAll(List.of("en", "fr")));

    CommandProcessor.getInstance().executeCommand(getProject(), () ->
      ModCommandExecutor.getInstance().executeInteractively(myFixture.getActionContext(), command, myFixture.getEditor()), null, null);

    IncompletePropertyInspection updated = (IncompletePropertyInspection)InspectionProfileManager.getInstance(getProject())
      .getCurrentProfile().getUnwrappedTool(IncompletePropertyInspection.TOOL_KEY, myFixture.getFile());
    assertTrue(updated.suffixes.containsAll(List.of("en", "fr")));
  }

  public void testAddMissingTranslation() {
    myFixture.addFileToProject("p_en.properties", "");
    myFixture.addFileToProject("p_fr.properties", "");
    myFixture.configureByText("p.properties", "<caret>key=value\n");

    assertNotNull(myFixture.findSingleIntention("Add missing translation"));
  }
}
