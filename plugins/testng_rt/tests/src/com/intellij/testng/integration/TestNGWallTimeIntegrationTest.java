// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.testng.integration;

import com.intellij.execution.ExecutionException;
import com.intellij.java.execution.AbstractTestFrameworkCompilingIntegrationTest;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.psi.PsiClass;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.containers.ContainerUtil;
import com.theoryinpractice.testng.configuration.TestNGConfiguration;
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage;
import jetbrains.buildServer.messages.serviceMessages.TestSuiteFinished;
import org.jetbrains.jps.model.library.JpsMavenRepositoryLibraryDescriptor;

public class TestNGWallTimeIntegrationTest extends AbstractTestFrameworkCompilingIntegrationTest {

  @Override
  protected void setupModule() throws Exception {
    super.setupModule();
    addMavenLibs(myModule, new JpsMavenRepositoryLibraryDescriptor("org.testng", "testng", "7.12.0"), getRepoManager());
  }

  @Override
  protected String getTestContentRoot() {
    return VfsUtilCore.pathToUrl(PlatformTestUtil.getCommunityPath() + "/plugins/testng_rt/tests/testData/integration/wallTime");
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      Registry.get("test.use.suite.duration").resetToDefault();
    }
    catch (Throwable e) {
      addSuppressedException(e);
    }
    finally {
      super.tearDown();
    }
  }

  public void testWallTimeEnabled() throws ExecutionException {
    // default value is true
    Registry.get("test.use.suite.duration").setValue(true);

    PsiClass psiClass = findClass(myModule, "a.WallTimeSuiteTest");
    assertNotNull(psiClass);
    TestNGConfiguration configuration = createConfiguration(psiClass);
    ProcessOutput processOutput = doStartTestsProcess(configuration);

    ServiceMessage enteredMatrix = ContainerUtil.find(processOutput.messages, m -> "enteredTheMatrix".equals(m.getMessageName()));
    assertEquals("MANUAL", enteredMatrix.getAttributes().get("durationStrategy"));

    ServiceMessage suite = ContainerUtil.find(processOutput.messages, TestSuiteFinished.class::isInstance);

    long duration = Long.parseLong(suite.getAttributes().get("duration"));
    assertTrue("Suite duration should be >= 300ms (200ms @BeforeClass + 100ms @Test) but was " + duration, duration >= 300);
  }

  public void testWallTimeDisabled() throws ExecutionException {
    Registry.get("test.use.suite.duration").setValue(false);

    PsiClass psiClass = findClass(myModule, "a.WallTimeSuiteTest");
    assertNotNull(psiClass);
    TestNGConfiguration configuration = createConfiguration(psiClass);
    ProcessOutput processOutput = doStartTestsProcess(configuration);

    ServiceMessage enteredMatrix = ContainerUtil.find(processOutput.messages, m -> "enteredTheMatrix".equals(m.getMessageName()));
    assertNull(enteredMatrix.getAttributes().get("durationStrategy"));

    ServiceMessage suite = ContainerUtil.find(processOutput.messages, TestSuiteFinished.class::isInstance);

    assertNull(suite.getAttributes().get("duration"));
  }
}
