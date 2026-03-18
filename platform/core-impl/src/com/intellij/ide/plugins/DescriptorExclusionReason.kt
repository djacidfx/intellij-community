// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:ApiStatus.Internal

package com.intellij.ide.plugins

import org.jetbrains.annotations.ApiStatus


sealed interface DescriptorExclusionReason {
  val descriptor: IdeaPluginDescriptorImpl
}

sealed interface ChainedExclusion {
  val precedingExcludedDescriptor: IdeaPluginDescriptorImpl
}

class DependencyIsNotResolved(
  override val descriptor: IdeaPluginDescriptorImpl,
  val dependency: PluginDependencyAnalysis.DependencyRef,
) : DescriptorExclusionReason

class DependencyIsExcluded(
  override val descriptor: IdeaPluginDescriptorImpl,
  val dependencyModule: PluginModuleDescriptor,
) : DescriptorExclusionReason, ChainedExclusion {
  override val precedingExcludedDescriptor: IdeaPluginDescriptorImpl get() = dependencyModule
}

class DependencyIsNotVisible(
  override val descriptor: IdeaPluginDescriptorImpl,
  val dependencyModule: PluginModuleDescriptor,
  val visibilityViolationLogMessage: String,
) : DescriptorExclusionReason

class IncompatibleWithAnotherModule(
  override val descriptor: IdeaPluginDescriptorImpl,
  val preferredIncompatibleModule: PluginModuleDescriptor,
) : DescriptorExclusionReason

class PartOfDependencyCycle(
  override val descriptor: IdeaPluginDescriptorImpl,
  val dependencyCycle: List<IdeaPluginDescriptorImpl>,
) : DescriptorExclusionReason

class PartOfRuntimeModuleGroupDependencyCycle(
  override val descriptor: IdeaPluginDescriptorImpl,
  val dependencyCycle: List<RuntimeModuleGroup>,
) : DescriptorExclusionReason

class DependsParentIsExcluded(
  override val descriptor: DependsSubDescriptor,
) : DescriptorExclusionReason, ChainedExclusion {
  override val precedingExcludedDescriptor: IdeaPluginDescriptorImpl
    get() = descriptor.parent
}

class ContentModuleParentIsExcluded(
  override val descriptor: ContentModuleDescriptor,
) : DescriptorExclusionReason, ChainedExclusion {
  override val precedingExcludedDescriptor: IdeaPluginDescriptorImpl
    get() = descriptor.parent
}

class RequiredContentModuleIsExcluded(
  override val descriptor: PluginMainDescriptor,
  val excludedContentModule: ContentModuleDescriptor,
) : DescriptorExclusionReason, ChainedExclusion {
  override val precedingExcludedDescriptor: IdeaPluginDescriptorImpl
    get() = excludedContentModule
}

class PackagePrefixConflictWithAnotherModule(
  override val descriptor: PluginModuleDescriptor,
  val preferredConflictingModule: PluginModuleDescriptor,
) : DescriptorExclusionReason

class ExcludedByEnvironmentConfiguration(
  override val descriptor: PluginModuleDescriptor,
  val reason: EnvironmentDependentModuleUnavailabilityReason,
) : DescriptorExclusionReason

class ProductRulesImposedExclusion(
  override val descriptor: PluginModuleDescriptor,
  val productReason: ProductRulesImposedExclusionReason,
) : DescriptorExclusionReason {
  interface ProductRulesImposedExclusionReason
}

fun DescriptorExclusionReason.getPrecedingLinkInExclusionChain(): IdeaPluginDescriptorImpl? =
  (this as? ChainedExclusion)?.precedingExcludedDescriptor

fun IdeaPluginDescriptorImpl.sequenceDescriptorExclusionChain(
  getExclusionReason: (IdeaPluginDescriptorImpl) -> DescriptorExclusionReason?,
): Sequence<IdeaPluginDescriptorImpl> {
  return sequence {
    var current: IdeaPluginDescriptorImpl? = this@sequenceDescriptorExclusionChain
    while (current != null) {
      val reason = getExclusionReason(current)
                   ?: break
      yield(current)
      current = reason.getPrecedingLinkInExclusionChain()
    }
  }
}