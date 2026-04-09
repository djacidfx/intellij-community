// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.junit5;

import com.intellij.execution.ExecutionException;
import com.intellij.java.execution.AbstractTestFrameworkCompilingIntegrationTest;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.psi.PsiClass;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.util.containers.ContainerUtil;
import jetbrains.buildServer.messages.serviceMessages.ServiceMessage;
import org.jetbrains.jps.model.library.JpsMavenRepositoryLibraryDescriptor;

public class JUnit5WallTimeIntegrationTest extends AbstractTestFrameworkCompilingIntegrationTest {

  @Override
  protected String getTestContentRoot() {
    return VfsUtilCore.pathToUrl(PlatformTestUtil.getCommunityPath() + "/plugins/junit5_rt_tests/testData/integration/wallTime5");
  }

  @Override
  protected void setupModule() throws Exception {
    super.setupModule();
    addMavenLibs(myModule, new JpsMavenRepositoryLibraryDescriptor("org.junit.jupiter", "junit-jupiter-api", "5.13.0"), getRepoManager());
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
    Registry.get("test.use.suite.duration").setValue(true);

    PsiClass psiClass = findClass(myModule, "org.example.WallTimeSuiteJUnit5Test");
    assertNotNull(psiClass);
    ProcessOutput processOutput = doStartTestsProcess(createConfiguration(psiClass));

    ServiceMessage enteredMatrix = ContainerUtil.find(processOutput.messages, m -> "enteredTheMatrix".equals(m.getMessageName()));
    assertNotNull(enteredMatrix);
    assertEquals("MANUAL", enteredMatrix.getAttributes().get("durationStrategy"));
  }

  public void testWallTimeDisabled() throws ExecutionException {
    Registry.get("test.use.suite.duration").setValue(false);

    PsiClass psiClass = findClass(myModule, "org.example.WallTimeSuiteJUnit5Test");
    assertNotNull(psiClass);
    ProcessOutput processOutput = doStartTestsProcess(createConfiguration(psiClass));

    ServiceMessage enteredMatrix = ContainerUtil.find(processOutput.messages, m -> "enteredTheMatrix".equals(m.getMessageName()));
    assertNotNull(enteredMatrix);
    assertNull(enteredMatrix.getAttributes().get("durationStrategy"));
  }
}
