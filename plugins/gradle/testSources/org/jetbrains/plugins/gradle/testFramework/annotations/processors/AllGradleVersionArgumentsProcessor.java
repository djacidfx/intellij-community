// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.testFramework.annotations.processors;

import org.gradle.util.GradleVersion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.testFramework.annotations.AllGradleVersionsSource;
import org.jetbrains.plugins.gradle.testFramework.annotations.ArgumentsProcessor;
import org.jetbrains.plugins.gradle.testFramework.annotations.GradleTestSource;
import org.jetbrains.plugins.gradle.tooling.annotation.TargetVersions;
import org.jetbrains.plugins.gradle.tooling.util.VersionMatcher;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;

import java.lang.annotation.Annotation;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.jetbrains.plugins.gradle.tooling.VersionMatcherRule.getSupportedGradleVersions;
import static org.jetbrains.plugins.gradle.tooling.VersionMatcherRule.isBoundarySupportedGradleVersion;

public class AllGradleVersionArgumentsProcessor extends DelegateArgumentsProcessor<AllGradleVersionsSource, GradleTestSource> {

  @Override
  public @NotNull ArgumentsProcessor<GradleTestSource> createArgumentsProcessor() {
    return new GradleTestArgumentsProcessor();
  }

  @Override
  public @NotNull GradleTestSource convertAnnotation(@NotNull AllGradleVersionsSource annotation) {
    return new GradleTestSource() {
      @Override
      public Class<? extends Annotation> annotationType() {
        return GradleTestSource.class;
      }

      @Override
      public String[] values() {
        return annotation.value();
      }

      @Override
      public String value() {
        return getSupportedGradleVersions().stream()
          .map(Objects::toString)
          .collect(Collectors.joining(","));
      }

      @Override
      public char separator() {
        return ',';
      }

      @Override
      public char delimiter() {
        return ':';
      }
    };
  }

  @Override
  public boolean filterArguments(@NotNull Arguments arguments, @NotNull ExtensionContext context) {
    var gradleVersion = (GradleVersion)arguments.get()[0];
    var targetVersions = context.getTestMethod()
      .map(it -> it.getAnnotation(TargetVersions.class))
      .orElse(null);

    if (!matchesTargetVersions(gradleVersion, targetVersions)) return false;

    String gradleVersionsToRunProp = System.getProperty("gradle.versions.to.run");
    if (gradleVersionsToRunProp == null || !gradleVersionsToRunProp.equals("FIRST_LAST")) {
      return true;
    }
    else {
      return isBoundarySupportedGradleVersion(gradleVersion, targetVersions);
    }
  }

  private static boolean matchesTargetVersions(@NotNull GradleVersion gradleVersion, @Nullable TargetVersions targetVersions) {
    if (targetVersions == null) return true;
    return new VersionMatcher(gradleVersion).isVersionMatch(targetVersions);
  }

  @Override
  public void accept(@NotNull AllGradleVersionsSource annotation) {
    super.accept(annotation);
  }
}
