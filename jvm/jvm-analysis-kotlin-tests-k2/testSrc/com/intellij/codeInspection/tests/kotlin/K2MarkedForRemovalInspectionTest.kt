package com.intellij.codeInspection.tests.kotlin

import com.intellij.idea.IgnoreJUnit3
import org.jetbrains.kotlin.idea.base.plugin.KotlinPluginMode
import org.junit.Ignore

@Ignore
@IgnoreJUnit3
class K2MarkedForRemovalInspectionTest : KotlinMarkedForRemovalInspectionTest() {
  override val pluginMode: KotlinPluginMode get() = KotlinPluginMode.K2
}