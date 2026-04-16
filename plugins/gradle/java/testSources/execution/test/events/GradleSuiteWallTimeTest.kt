// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.plugins.gradle.execution.test.events

import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.events.TestDurationStrategy
import com.intellij.testFramework.junit5.RegistryKey
import org.assertj.core.api.Assertions
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.testFramework.GradleTestExecutionTestCase
import org.jetbrains.plugins.gradle.testFramework.annotations.BaseGradleVersionSource
import org.junit.jupiter.params.ParameterizedTest

class GradleSuiteWallTimeTest : GradleTestExecutionTestCase() {

  @ParameterizedTest
  @BaseGradleVersionSource
  @RegistryKey(key = "test.use.suite.duration", value = "true")
  fun `test suite wall time includes class setup`(gradleVersion: GradleVersion) {
    testJunit4Project(gradleVersion) {
      writeText("src/test/java/org/example/SuiteWithSetup.java", """
        |package org.example;
        |import org.junit.BeforeClass;
        |import org.junit.Test;
        |public class SuiteWithSetup {
        |  @BeforeClass
        |  public static void setUpClass() throws InterruptedException {
        |    Thread.sleep(200);
        |  }
        |  @Test
        |  public void test() throws InterruptedException {
        |    Thread.sleep(100);
        |  }
        |}
      """.trimMargin())

      executeTasks(":test", isRunAsTest = true)
      assertTestViewTree {
        assertNode("SuiteWithSetup") {
          assertValue { suite ->
            Assertions.assertThat((suite as SMTestProxy).durationStrategy)
              .describedAs("Duration strategy should be MANUAL when wall time is enabled")
              .isEqualTo(TestDurationStrategy.MANUAL)
            Assertions.assertThat(suite.duration)
              .describedAs("Suite wall time should include @BeforeClass (200ms) + @Test (100ms)")
              .isGreaterThanOrEqualTo(300L)
          }
          assertNode("test")
        }
      }
    }
  }

  @ParameterizedTest
  @BaseGradleVersionSource
  @RegistryKey(key = "test.use.suite.duration", value = "false")
  fun `test suite uses AUTOMATIC duration strategy when wall time is disabled`(gradleVersion: GradleVersion) {
    testJunit4Project(gradleVersion) {
      writeText("src/test/java/org/example/SuiteWithSetup.java", """
        |package org.example;
        |import org.junit.Test;
        |public class SuiteWithSetup {
        |  @Test public void test() {}
        |}
      """.trimMargin())

      executeTasks(":test", isRunAsTest = true)
      assertTestViewTree {
        assertNode("SuiteWithSetup") {
          assertValue { suite ->
            Assertions.assertThat((suite as SMTestProxy).durationStrategy)
              .describedAs("Duration strategy should not be MANUAL when wall time is disabled")
              .isNotEqualTo(TestDurationStrategy.MANUAL)
          }
          assertNode("test")
        }
      }
    }
  }
}
