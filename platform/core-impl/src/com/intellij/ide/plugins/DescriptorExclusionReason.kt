// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:ApiStatus.Internal

package com.intellij.ide.plugins

import org.jetbrains.annotations.ApiStatus


sealed interface DescriptorExclusionReason

class DependencyIsNotResolved(
  val dependency: PluginDependencyAnalysis.DependencyRef,
) : DescriptorExclusionReason

class DependencyIsExcluded(
  val dependencyModule: PluginModuleDescriptor,
) : DescriptorExclusionReason

class DependencyIsNotVisible(
  val dependencyModule: PluginModuleDescriptor,
  val visibilityViolationLogMessage: String,
) : DescriptorExclusionReason

class IncompatibleWithAnotherModule(
  val preferredIncompatibleModule: PluginModuleDescriptor,
) : DescriptorExclusionReason

class PartOfDependencyCycle(
  val dependencyCycle: List<IdeaPluginDescriptorImpl>,
) : DescriptorExclusionReason

class PartOfRuntimeModuleGroupDependencyCycle(
  val dependencyCycle: List<RuntimeModuleGroup>,
) : DescriptorExclusionReason

class DependsParentIsExcluded : DescriptorExclusionReason

class ContentModuleParentIsExcluded : DescriptorExclusionReason

class RequiredContentModuleIsExcluded(
  val excludedContentModule: ContentModuleDescriptor,
) : DescriptorExclusionReason

class PackagePrefixConflictWithAnotherModule(
  val preferredConflictingModule: PluginModuleDescriptor,
) : DescriptorExclusionReason

class ExcludedByEnvironmentConfiguration(
  val reason: EnvironmentDependentModuleUnavailabilityReason,
) : DescriptorExclusionReason

class ProductRulesImposedExclusion(
  val reason: ProductRulesImposedExclusionReason,
) : DescriptorExclusionReason {
  interface ProductRulesImposedExclusionReason
}
