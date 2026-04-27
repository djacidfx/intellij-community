// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.bazel

import com.intellij.openapi.util.JDOMUtil
import org.jdom.Element
import java.nio.file.Path

internal data class KotlincProjectDefaults(
  val jvmTarget: String,
  val apiVersion: String,
  val languageVersion: String,
  val optIn: List<String>,
  val progressive: Boolean,
  val jvmDefault: String,
  val rawJvmDefault: String?,
  val xxLanguage: List<String>,
)

internal fun parseKotlincProjectDefaults(projectDir: Path): KotlincProjectDefaults =
  parseKotlincProjectDefaultsFromXml(projectDir.resolve(".idea/kotlinc.xml"))

private const val OPT_IN_PREFIX = "-opt-in="
private const val PROGRESSIVE_FLAG = "-progressive"
private const val XJVM_DEFAULT_PREFIX = "-Xjvm-default="
private const val XX_LANGUAGE_PREFIX = "-XXLanguage:"

private val KOTLIN2_JVM_KNOWN_OPTIONS = setOf("jvmTarget")
private val KOTLIN_COMMON_KNOWN_OPTIONS = setOf("apiVersion", "languageVersion")
private val KOTLIN_COMPILER_SETTINGS_KNOWN_OPTIONS = setOf("additionalArguments")
private val KOTLIN_JPS_PLUGIN_KNOWN_OPTIONS = setOf("version")

internal fun parseKotlincProjectDefaultsFromXml(kotlincXml: Path): KotlincProjectDefaults {
  val xml = JDOMUtil.load(kotlincXml)

  val jvmComponent = requireComponent(xml, "Kotlin2JvmCompilerArguments", kotlincXml)
  val commonComponent = requireComponent(xml, "KotlinCommonCompilerArguments", kotlincXml)
  val compilerSettings = requireComponent(xml, "KotlinCompilerSettings", kotlincXml)
  val jpsPluginSettings = requireComponent(xml, "KotlinJpsPluginSettings", kotlincXml)

  val jvmTarget = requireOption(jvmComponent, "jvmTarget", "Kotlin2JvmCompilerArguments", kotlincXml)
  rejectUnknownOptions(jvmComponent, "Kotlin2JvmCompilerArguments", KOTLIN2_JVM_KNOWN_OPTIONS, kotlincXml)

  val apiVersion = requireOption(commonComponent, "apiVersion", "KotlinCommonCompilerArguments", kotlincXml)
  val languageVersion = requireOption(commonComponent, "languageVersion", "KotlinCommonCompilerArguments", kotlincXml)
  rejectUnknownOptions(commonComponent, "KotlinCommonCompilerArguments", KOTLIN_COMMON_KNOWN_OPTIONS, kotlincXml)

  val additionalArguments = requireOption(compilerSettings, "additionalArguments", "KotlinCompilerSettings", kotlincXml)
  rejectUnknownOptions(compilerSettings, "KotlinCompilerSettings", KOTLIN_COMPILER_SETTINGS_KNOWN_OPTIONS, kotlincXml)

  rejectUnknownOptions(jpsPluginSettings, "KotlinJpsPluginSettings", KOTLIN_JPS_PLUGIN_KNOWN_OPTIONS, kotlincXml)

  val optIn = mutableListOf<String>()
  var progressive = false
  var rawJvmDefault: String? = null
  val xxLanguage = mutableListOf<String>()

  for (token in additionalArguments.split(" ").filter { it.isNotEmpty() }) {
    when {
      token.startsWith(OPT_IN_PREFIX) -> optIn += token.removePrefix(OPT_IN_PREFIX)
      token == PROGRESSIVE_FLAG -> progressive = true
      token.startsWith(XJVM_DEFAULT_PREFIX) -> {
        val value = token.removePrefix(XJVM_DEFAULT_PREFIX)
        check(rawJvmDefault == null) {
          "Duplicate $XJVM_DEFAULT_PREFIX in $kotlincXml KotlinCompilerSettings.additionalArguments"
        }
        rawJvmDefault = value
      }
      token.startsWith(XX_LANGUAGE_PREFIX) -> xxLanguage += token.removePrefix(XX_LANGUAGE_PREFIX)
      else -> error(
        "Unsupported Kotlin compiler option in $kotlincXml KotlinCompilerSettings.additionalArguments: '$token'. " +
        "To support it, extend parseKotlincProjectDefaults() and the create_kotlinc_options template in CompilerOptionsBzlGenerator."
      )
    }
  }

  val jvmDefault = if (rawJvmDefault != null) normalizeLegacyJvmDefault(rawJvmDefault, kotlincXml) else "no-compatibility"

  return KotlincProjectDefaults(
    jvmTarget = jvmTarget,
    apiVersion = apiVersion,
    languageVersion = languageVersion,
    optIn = optIn.toList(),
    progressive = progressive,
    jvmDefault = jvmDefault,
    rawJvmDefault = rawJvmDefault,
    xxLanguage = xxLanguage.toList(),
  )
}

private fun normalizeLegacyJvmDefault(rawValue: String, kotlincXml: Path): String = when (rawValue) {
  "disable" -> "disable"
  "all-compatibility" -> "enable"
  "all" -> "no-compatibility"
  else -> error(
    "Unsupported -Xjvm-default value '$rawValue' in $kotlincXml KotlinCompilerSettings.additionalArguments. " +
    "To support it, extend parseKotlincProjectDefaults() and the create_kotlinc_options template in CompilerOptionsBzlGenerator."
  )
}

private fun requireComponent(root: Element, componentName: String, kotlincXml: Path): Element =
  root.getChildren("component").singleOrNull { it.getAttributeValue("name") == componentName }
  ?: error("$componentName component not found in $kotlincXml")

private fun requireOption(component: Element, optionName: String, componentName: String, kotlincXml: Path): String {
  val option = component.getChildren("option").singleOrNull { it.getAttributeValue("name") == optionName }
               ?: error("$optionName option not found in $componentName in $kotlincXml")
  return option.getAttributeValue("value")
         ?: error("$optionName option in $componentName in $kotlincXml is missing the 'value' attribute")
}

private fun rejectUnknownOptions(component: Element, componentName: String, knownOptions: Set<String>, kotlincXml: Path) {
  for (option in component.getChildren("option")) {
    val name = option.getAttributeValue("name")
    if (name !in knownOptions) {
      error(
        "Unsupported option '$name' in $componentName in $kotlincXml. " +
        "To support it, extend parseKotlincProjectDefaults() and the create_kotlinc_options template in CompilerOptionsBzlGenerator."
      )
    }
  }
}
