// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.testFramework.util

import com.intellij.gradle.toolingExtension.util.GradleVersionUtil
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isConfigurationCacheSupported
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isDependencyResolutionManagementSupported
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isGroovy5Supported
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isIsolatedProjectsSupported
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isJavaConventionsBlockSupported
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isJunit5Supported
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isKotlinDslScriptsModelImportSupported
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isKotlinSupported
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isSpockSupported
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isTopLevelJavaConventionsSupported
import org.jetbrains.plugins.gradle.frameworkSupport.buildscript.isVersionCatalogsSupported
import org.junit.jupiter.api.Assertions


fun assertThatGradleIsAtLeast(gradleVersion: GradleVersion, version: String) {
  assertThatGradleIsAtLeast(gradleVersion, version) {
    """
      Test cannot be executed on Gradle versions older than $version.
      Please, use @TargetVersions("$version+") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatGradleIsAtLeast(gradleVersion: GradleVersion, version: String, lazyMessage: () -> String) {
  Assertions.assertTrue(GradleVersionUtil.isGradleAtLeast(gradleVersion, version), lazyMessage)
}

fun assertThatGradleIsOlderThan(gradleVersion: GradleVersion, version: String) {
  assertThatGradleIsOlderThan(gradleVersion, version) {
    """
      Test cannot be executed on Gradle versions newer than $version.
      Please, use @TargetVersions("<$version") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatGradleIsOlderThan(gradleVersion: GradleVersion, version: String, lazyMessage: () -> String) {
  Assertions.assertTrue(GradleVersionUtil.isGradleOlderThan(gradleVersion, version), lazyMessage)
}

fun assertThatJunit5IsSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isJunit5Supported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support Junit 5.
      Please, use @TargetVersions("4.7+") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatGroovy5IsSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isGroovy5Supported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support Groovy 5.
      Please, use @TargetVersions("7.0+") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatKotlinIsSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isKotlinSupported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support Kotlin.
      Please, use @TargetVersions("5.6.2+") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatKotlinDslScriptsModelImportIsSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isKotlinDslScriptsModelImportSupported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support KotlinDslScriptsModel import.
      Please, use @TargetVersions("6.0+") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatSpockIsSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isSpockSupported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support Spock.
      Please, use @TargetVersions("5.6+") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatTopLevelJavaConventionsIsSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isTopLevelJavaConventionsSupported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support top-level java conventions.
      Please, use @TargetVersions("<8.2") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatJavaConventionsBlockIsSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isJavaConventionsBlockSupported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support java conventions block.
      Please, use @TargetVersions("7.1+") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatConfigurationCacheIsSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isConfigurationCacheSupported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support stable configuration caches.
      Please, use @TargetVersions("8.1+") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatIsolatedProjectsIsSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isIsolatedProjectsSupported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support isolated projects.
      Please, use @TargetVersions("8.8+") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatVersionCatalogsAreSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isVersionCatalogsSupported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support version catalogs.
      Please, use @TargetVersions("7.4+") annotation to ignore this version.
    """.trimIndent()
  }
}

fun assertThatDependencyResolutionManagementIsSupported(gradleVersion: GradleVersion) {
  Assertions.assertTrue(isDependencyResolutionManagementSupported(gradleVersion)) {
    """
      Gradle ${gradleVersion.version} doesn't support DependencyResolutionManagement.
      Please use @TargetVersions("6.8+") annotation to ignore this version.
    """.trimIndent()
  }
}
