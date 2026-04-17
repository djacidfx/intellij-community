// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.tests;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

class JUnit5BazelRunnerTest {
  @Test
  void discoversJupiterAndVintageTestsByDefault() {
    assertThat(discoverTestClasses(null)).containsExactlyInAnyOrder(
      VintageSampleTest.class.getName(),
      JupiterSampleTest.class.getName()
    );
  }

  @Test
  void excludesVintageTestsWhenVintageEngineIsDisabled() {
    assertThat(discoverTestClasses("false")).containsExactly(JupiterSampleTest.class.getName());
  }

  @Test
  void discoversOnlyVintageTestsWhenRequested() {
    assertThat(discoverTestClasses("only")).containsExactly(VintageSampleTest.class.getName());
  }

  @Test
  void rejectsUnsupportedVintageMode() {
    assertThatThrownBy(() -> JUnit5BazelRunner.createDiscoveryRequest(selectors(), "unsupportedEngine"))
      .isInstanceOf(RuntimeException.class)
      .hasMessageContaining("unsupportedEngine");
  }

  private static Set<String> discoverTestClasses(String engineVintage) {
    var request = JUnit5BazelRunner.createDiscoveryRequest(selectors(), engineVintage);
    var launcher = LauncherFactory.create(LauncherConfig.builder()
                                         .enableLauncherSessionListenerAutoRegistration(false)
                                         .build());
    var testPlan = launcher.discover(request);
    return testPlan
      .getRoots()
      .stream()
      .flatMap(root -> testPlan.getDescendants(root).stream())
      .filter(TestIdentifier::isTest)
      .map(JUnit5BazelRunnerTest::getClassName)
      .collect(Collectors.toSet());
  }

  private static List<DiscoverySelector> selectors() {
    return List.of(selectClass(VintageSampleTest.class), selectClass(JupiterSampleTest.class));
  }

  private static String getClassName(TestIdentifier testIdentifier) {
    return testIdentifier.getSource()
      .map(source -> {
        if (source instanceof MethodSource methodSource) {
          return methodSource.getClassName();
        }
        if (source instanceof ClassSource classSource) {
          return classSource.getClassName();
        }
        throw new AssertionError("Unexpected source: " + source);
      })
      .orElseThrow(() -> new AssertionError("Missing source for " + testIdentifier.getUniqueId()));
  }

  public static class VintageSampleTest {
    @org.junit.Test
    public void vintageTest() {
    }
  }

  public static class JupiterSampleTest {
    @Test
    void jupiterTest() {
    }
  }
}
