// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins

import com.intellij.ide.plugins.PluginSetConstraintsResolver.CandidateState.Candidate
import com.intellij.ide.plugins.PluginSetConstraintsResolver.CandidateState.Excluded
import com.intellij.util.graph.DFSTBuilder
import com.intellij.util.graph.OutboundSemiGraph
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
fun PluginInitializationContext.resolveConstraints(
  pluginSet: UnambiguousPluginSet,
): ResolvedPluginSet {
  val resolver = PluginSetConstraintsResolver(this, pluginSet)
  return resolver.resolveConstraints()
}

private class PluginSetConstraintsResolver(
  private val initContext: PluginInitializationContext,
  private val pluginSet: UnambiguousPluginSet,
) {
  private sealed class CandidateState {
    class Excluded(val reason: DescriptorExclusionReason) : CandidateState()
    class Candidate(var listeners: ArrayList<ExclusionListenerData>? = null) : CandidateState() {
      fun addListener(listener: ExclusionListenerData) {
        if (listeners == null) {
          listeners = ArrayList()
        }
        listeners!!.add(listener)
      }
    }
  }

  private val candidates: HashMap<IdeaPluginDescriptorImpl, CandidateState>

  private fun IdeaPluginDescriptorImpl.getState(): CandidateState = candidates[this] ?: error("Unknown descriptor: $this")

  init {
    val allDescriptors = pluginSet.sequenceAllDescriptors().toList()
    candidates = HashMap(allDescriptors.size)
    for (descriptor in allDescriptors) {
      candidates[descriptor] = Candidate()
    }
  }

  fun resolveConstraints(): ResolvedPluginSet {
    applyEnvironmentConfiguredExclusions()
    applyProductRulesImposedExclusions()

    val constraintBuilders = listOf(
      ::establishPluginIntegrityConstraints,
      ::establishDependencyFulfillmentConstraintsAndRememberResolvedDependencies,
      ::rememberIncompatibleWithViolations,
      ::rememberPackagePrefixRegistration
    )

    for (candidate in candidates.keys) {
      for (builder in constraintBuilders) {
        if (candidate.getState() is Excluded) break
        builder(candidate)
      }
    }

    resolveRemainingIncompatibleWithViolations()
    resolveRemainingPackagePrefixConflicts()

    while (true) {
      // if there are cycles, at least one candidate gets excluded, so the process is finite;
      // in fact, candidates are excluded in groups, so it should converge pretty fast;
      // we don't expect cycles, so if they happen, it is a slow path anyway
      tryBuildRuntimeModuleGroupDAGOrExcludeCycles()?.let {
        return it
      }
    }
  }

  /** DFS exclusion semantics */
  private fun exclude(candidate: IdeaPluginDescriptorImpl, reason: DescriptorExclusionReason) {
    when (val state = candidate.getState()) {
      is Excluded -> return
      is Candidate -> {
        candidates[candidate] = Excluded(reason)
        state.listeners?.forEach { listener ->
          processExclusionListener(candidate, listener)
        }
      }
    }
  }

  private fun processExclusionListener(excludedDescriptor: IdeaPluginDescriptorImpl, data: ExclusionListenerData) {
    when (data) {
      is ExcludeDependsDescriptorOnParentExclusion ->
        exclude(data.dependsDescriptor, DependsParentIsExcluded(data.dependsDescriptor))
      is ExcludeContentModuleOnPluginExclusion ->
        exclude(data.contentModule, ContentModuleParentIsExcluded(data.contentModule))
      is ExcludePluginOnRequiredContentModuleExclusion ->
        exclude(data.plugin, RequiredContentModuleIsExcluded(data.plugin, excludedDescriptor as ContentModuleDescriptor))
      is ExcludeDependentDescriptorOnModuleExclusion ->
        exclude(data.dependentDescriptor, DependencyIsExcluded(data.dependentDescriptor, excludedDescriptor as PluginModuleDescriptor))
    }
  }

  /** Special case for dependency cycle exclusions */
  private fun batchExclude(candidatesToExclude: Collection<IdeaPluginDescriptorImpl>, reasonBuilder: (IdeaPluginDescriptorImpl) -> DescriptorExclusionReason) {
    val deferredListeners = ArrayList<Pair<IdeaPluginDescriptorImpl, ArrayList<ExclusionListenerData>>>()
    for (candidate in candidatesToExclude) {
      when (val state = candidate.getState()) {
        is Excluded -> continue
        is Candidate -> {
          candidates[candidate] = Excluded(reasonBuilder(candidate))
          state.listeners?.let { deferredListeners.add(candidate to it) }
        }
      }
    }
    for ((candidate, listeners) in deferredListeners) {
      listeners.forEach { listener ->
        processExclusionListener(candidate, listener)
      }
    }
  }

  private fun applyProductRulesImposedExclusions() {
    for ((module, reason) in initContext.provideModuleExclusionsImposedByProductRules(pluginSet)) {
      exclude(module, ProductRulesImposedExclusion(module, reason))
    }
  }

  private fun applyEnvironmentConfiguredExclusions() {
    for ((moduleId, envConfig) in initContext.environmentConfiguredModules) {
      val module = pluginSet.resolveContentModuleId(moduleId) ?: error("Environment-configured module is not found: $moduleId")
      if (envConfig.unavailabilityReason != null) {
        exclude(module, ExcludedByEnvironmentConfiguration(module, envConfig.unavailabilityReason))
      }
    }
  }

  /**
   * 1. if a depends-descriptor's parent is excluded, it is excluded too;
   * 2. if a content module's plugin is excluded, it is excluded too;
   * 3. if a required content module is excluded, its main plugin descriptor is excluded too.
   */
  private fun establishPluginIntegrityConstraints(candidate: IdeaPluginDescriptorImpl) {
    when (candidate) {
      is DependsSubDescriptor ->
        when (val parentState = candidate.parent.getState()) {
          is Excluded -> exclude(candidate, DependsParentIsExcluded(candidate))
          is Candidate -> parentState.addListener(ExcludeDependsDescriptorOnParentExclusion(candidate))
        }
      is ContentModuleDescriptor ->
        when (val parentState = candidate.parent.getState()) {
          is Excluded -> exclude(candidate, ContentModuleParentIsExcluded(candidate))
          is Candidate -> parentState.addListener(ExcludeContentModuleOnPluginExclusion(candidate))
        }
      is PluginMainDescriptor -> {
        for (contentModule in candidate.contentModules) {
          if (!contentModule.isRequiredContentModule) {
            continue
          }
          when (val contentModuleState = contentModule.getState()) {
            is Excluded -> {
              exclude(candidate, RequiredContentModuleIsExcluded(candidate, contentModule))
              break
            }
            is Candidate -> contentModuleState.addListener(ExcludePluginOnRequiredContentModuleExclusion(candidate))
          }
        }
      }
    }
    // TODO: do we want to support non-optional `depends` with a sub-descriptor?
  }

  private fun sequenceAllDependenciesOfCandidateIncludingCompatibility(candidate: IdeaPluginDescriptorImpl): Sequence<PluginDependencyAnalysis.DependencyRef> {
    return PluginDependencyAnalysis.sequenceStrictDependencies(candidate) + initContext.provideCompatibilityDependencies(candidate, pluginSet)
  }

  /**
   * Stores a **full** list of descriptor's dependencies, i.e., such modules (and descriptors) that must be registered
   * by the application before ours and whose resources and classes must be available in our classloader.
   */
  private val resolvedFullDependenciesLists: HashMap<IdeaPluginDescriptorImpl, List<IdeaPluginDescriptorImpl>> = HashMap()

  private fun getResolvedFullDependenciesList(candidate: IdeaPluginDescriptorImpl): List<IdeaPluginDescriptorImpl> {
    return resolvedFullDependenciesLists[candidate] ?: error("Dependency list is not resolved for $candidate")
  }

  /**
   * For all strict dependencies and implicit dependencies provided by [PluginInitializationContext.provideCompatibilityDependencies]:
   * 1. if dependency is not resolved, the candidate is excluded;
   * 2. if dependency is excluded, the candidate is excluded;
   * 3. if dependency violates visibility rules, the candidate is excluded.
   */
  private fun establishDependencyFulfillmentConstraintsAndRememberResolvedDependencies(candidate: IdeaPluginDescriptorImpl) {
    val resolvedDependencies = ArrayList<IdeaPluginDescriptorImpl>()
    val seenDependencies = HashMap<IdeaPluginDescriptorImpl, Boolean>()
    fun tryAddDependency(dependency: IdeaPluginDescriptorImpl): Boolean {
      if (dependency !== candidate && seenDependencies.putIfAbsent(dependency, true) == null) {
        resolvedDependencies.add(dependency)
        return true
      }
      return false
    }
    for (dependencyRef in sequenceAllDependenciesOfCandidateIncludingCompatibility(candidate)) {
      val target = pluginSet.resolveReference(dependencyRef)
      if (target == null) {
        exclude(candidate, DependencyIsNotResolved(candidate, dependencyRef))
        return
      }
      else if (tryAddDependency(target)) {
        if (target is ContentModuleDescriptor) {
          val visibilityViolation = PluginSetBuilder.checkVisibilityAndReturnErrorMessage(
            candidate as? ContentModuleDescriptor ?: candidate.getMainDescriptor(),
            target
          )
          if (visibilityViolation != null) {
            exclude(candidate, DependencyIsNotVisible(candidate, target, visibilityViolation))
            return
          }
        }
        when (val targetState = target.getState()) {
          is Excluded -> {
            exclude(candidate, DependencyIsExcluded(candidate, target))
            return
          }
          is Candidate -> targetState.addListener(ExcludeDependentDescriptorOnModuleExclusion(candidate))
        }
      }
    }
    // handle implicit dependencies
    when (candidate) {
      is PluginMainDescriptor -> {
        // TODO: if we don't set up dependencies from main to embedded modules, embedded modules may be registered after main. Does main expect that EPs from embedded modules are available?
        for (contentModule in candidate.contentModules) {
          if (contentModule.moduleLoadingRule == ModuleLoadingRule.EMBEDDED) {
            tryAddDependency(contentModule)
          }
        }
      }
      is ContentModuleDescriptor -> {
        if (candidate.moduleLoadingRule == ModuleLoadingRule.OPTIONAL) {
          // there is an implicit dependency on main
          tryAddDependency(candidate.parent)
        }
      }
      is DependsSubDescriptor -> {
        // `<depends>`' parent must be registered before the sub-descriptor
        tryAddDependency(candidate.parent)
      }
    }
    resolvedFullDependenciesLists[candidate] = resolvedDependencies
  }

  private val essentialModulesClosure: Set<PluginModuleDescriptor> by lazy {
    // TODO: does not handle implicitly added dependencies. Is it a big problem?
    PluginDependencyAnalysis.getRequiredTransitiveModules(
      initContext = initContext,
      plugins = initContext.essentialPlugins.mapNotNull { pluginSet.resolvePluginId(it) },
      ambiguousPluginSet = pluginSet.asAmbiguousPluginSet(),
      null
    )
  }

  private tailrec fun IdeaPluginDescriptorImpl.isEssential(): Boolean {
    return when (this) {
      is PluginModuleDescriptor -> this in essentialModulesClosure
      is DependsSubDescriptor -> { // TODO do we even want to support such cases?
        val dependency = parent.pluginDependencies.find { it.subDescriptor === this } ?: error("unattached depends sub-descriptor: $this")
        if (dependency.isOptional) false
        else parent.isEssential()
      }
    }
  }

  val incompatibleWithEdges = ArrayList<Pair<IdeaPluginDescriptorImpl, PluginModuleDescriptor>>()

  private fun rememberIncompatibleWithViolations(candidate: IdeaPluginDescriptorImpl) {
    for (incompatiblePluginId in candidate.incompatiblePlugins) {
      val target = pluginSet.resolvePluginId(incompatiblePluginId)
      if (target != null && target.getState() is Candidate) {
        incompatibleWithEdges.add(candidate to target)
      }
    }
  }

  private fun resolveRemainingIncompatibleWithViolations() {
    for ((candidate, incompatiblePlugin) in incompatibleWithEdges) {
      if (candidate.getState() is Excluded || incompatiblePlugin.getState() is Excluded) {
        continue
      }
      val (survivor, excluded) = if (candidate.isEssential()) {
        candidate to incompatiblePlugin
      }
      else {
        incompatiblePlugin to candidate
      }
      exclude(excluded, IncompatibleWithAnotherModule(
        candidate, survivor as? PluginModuleDescriptor ?: survivor.getMainDescriptor()
      ))
    }
  }

  private val packagePrefixRegistrations = HashMap<String, ArrayList<PluginModuleDescriptor>>()

  private fun rememberPackagePrefixRegistration(candidate: IdeaPluginDescriptorImpl) {
    if (candidate !is PluginModuleDescriptor) {
      return
    }
    if (candidate.packagePrefix != null) {
      packagePrefixRegistrations.getOrPut(candidate.packagePrefix) { ArrayList() }.add(candidate)
    }
  }

  private fun resolveRemainingPackagePrefixConflicts() {
    for (conflictingModules in packagePrefixRegistrations.values) {
      var currentSurvivor: PluginModuleDescriptor? = null
      for (candidate in conflictingModules) {
        if (candidate.getState() is Excluded) {
          continue
        }
        if (currentSurvivor == null) {
          currentSurvivor = candidate
        }
        else {
          val (survivor, excluded) = if (currentSurvivor.isEssential()) {
            currentSurvivor to candidate
          }
          else {
            candidate to currentSurvivor
          }
          exclude(excluded, PackagePrefixConflictWithAnotherModule(excluded, survivor))
          currentSurvivor = survivor
        }
      }
    }
  }


  private fun tryBuildRuntimeModuleGroupDAGOrExcludeCycles(): ResolvedPluginSet? {
    val sortedCandidates = sortRemainingCandidatesTopologicallyOrExcludeCycles()
                           ?: return null
    val runtimeModuleGroupGraph = buildAcyclicRuntimeModuleGroupGraphOrExcludeCycles(sortedCandidates)
                                  ?: return null

    // preserves all keys for 'unknown descriptor' check
    val exclusions = candidates.mapValuesTo(HashMap(candidates.size)) { (it.value as? Excluded)?.reason }
    val resolvedPluginSet = ResolvedPluginSetImpl(
      originalPluginSet = pluginSet,
      initContext = initContext,
      sortedResolvedDescriptors = LinkedHashSet(sortedCandidates),
      runtimeModuleGroupGraph = runtimeModuleGroupGraph,
      exclusions = exclusions,
      resolvedDependencies = resolvedFullDependenciesLists.filterKeys { it.getState() is Candidate }
    )
    return resolvedPluginSet
  }

  private fun sortRemainingCandidatesTopologicallyOrExcludeCycles(): List<IdeaPluginDescriptorImpl>? {
    val remainingCandidates = candidates.keys.filterTo(ArrayList()) { it.getState() is Candidate }
    val descriptorGraph = DFSTBuilder(DescriptorGraphAdapter(remainingCandidates))
    if (!descriptorGraph.isAcyclic) {
      for (component in descriptorGraph.components) {
        if (component.size <= 1) {
          continue
        }
        val dependencyCycle = component.toList()
        batchExclude(dependencyCycle) { PartOfDependencyCycle(it, dependencyCycle) }
      }
      return null
    }
    remainingCandidates.sortWith(descriptorGraph.comparator())
    return remainingCandidates
  }

  private typealias RepresentativeModule = PluginModuleDescriptor

  private fun buildAcyclicRuntimeModuleGroupGraphOrExcludeCycles(sortedCandidates: List<IdeaPluginDescriptorImpl>): RuntimeModuleGroupGraphImpl? {
    val representativeToGroups = HashMap<RepresentativeModule, RuntimeModuleGroupImpl>(sortedCandidates.size)
    val candidateToGroup = HashMap<IdeaPluginDescriptorImpl, RuntimeModuleGroup>(sortedCandidates.size)
    for (candidate in sortedCandidates) {
      val representative = getRuntimeModuleGroupRepresentative(candidate)
      val group = representativeToGroups.getOrPut(representative) { RuntimeModuleGroupImpl(representative) }
      group.sortedDescriptors.add(candidate)
      candidateToGroup[candidate] = group
    }
    val groupToGroupDependencies = HashMap<RuntimeModuleGroup, List<RuntimeModuleGroup>>()
    for (group in representativeToGroups.values) {
      val groupDependencies = ArrayList<RuntimeModuleGroup>()
      val descriptors = group.sortedDescriptors
      val seenDependencies = HashMap<RuntimeModuleGroupImpl, Boolean>()
      for (descriptor in descriptors) {
        for (target in getResolvedFullDependenciesList(descriptor)) {
          val targetGroup = candidateToGroup[target] as? RuntimeModuleGroupImpl ?: error("runtime module group not found for $target")
          if (targetGroup === group) {
            continue
          }
          if (seenDependencies.putIfAbsent(targetGroup, true) == null) {
            groupDependencies.add(targetGroup)
          }
        }
      }
      groupToGroupDependencies[group] = groupDependencies
    }

    val dfstBuilder = DFSTBuilder(RuntimeModuleGroupGraphAdapter(representativeToGroups.values, groupToGroupDependencies))
    if (!dfstBuilder.isAcyclic) {
      for (component in dfstBuilder.components) {
        if (component.size <= 1) {
          continue
        }
        val groupDependencyCycle = component.toList()
        val descriptors = groupDependencyCycle.flatMap { it.sortedDescriptors }
        // try to exclude only non-essential at first (e.g., it may be possible to eliminate cycles by dropping some optional `depends` descriptors first)
        val toExclude = descriptors.filter { !it.isEssential() }.takeIf { it.isNotEmpty() } ?: descriptors
        batchExclude(toExclude) { PartOfRuntimeModuleGroupDependencyCycle(it, groupDependencyCycle) }
      }
      return null
    }
    val runtimeModuleGroupGraph = RuntimeModuleGroupGraphImpl(
      sortedGroups = representativeToGroups.values.sortedWith(dfstBuilder.comparator()),
      dependencies = groupToGroupDependencies,
      descriptorToGroup = candidateToGroup
    )
    return runtimeModuleGroupGraph
  }

  /** finds a representative module for the runtime module group current [candidate] belongs to */
  private tailrec fun getRuntimeModuleGroupRepresentative(candidate: IdeaPluginDescriptorImpl): PluginModuleDescriptor {
    return when (candidate) {
      is PluginMainDescriptor -> candidate
      is ContentModuleDescriptor -> {
        if (candidate.moduleLoadingRule == ModuleLoadingRule.EMBEDDED) {
          getRuntimeModuleGroupRepresentative(candidate.parent)
        }
        else {
          candidate
        }
      }
      is DependsSubDescriptor -> {
        getRuntimeModuleGroupRepresentative(candidate.getMainDescriptor())
      }
    }
  }

  private inner class DescriptorGraphAdapter(val remainingCandidates: List<IdeaPluginDescriptorImpl>) : OutboundSemiGraph<IdeaPluginDescriptorImpl> {
    override fun getNodes(): Collection<IdeaPluginDescriptorImpl> = remainingCandidates
    override fun getOut(node: IdeaPluginDescriptorImpl): Iterator<IdeaPluginDescriptorImpl> {
      return getResolvedFullDependenciesList(node).iterator()
    }
  }

  private class RuntimeModuleGroupGraphAdapter(val groups: Collection<RuntimeModuleGroup>, val dependencies: Map<RuntimeModuleGroup, List<RuntimeModuleGroup>>) :
    OutboundSemiGraph<RuntimeModuleGroup> {
    override fun getNodes(): Collection<RuntimeModuleGroup> = groups
    override fun getOut(node: RuntimeModuleGroup): Iterator<RuntimeModuleGroup> {
      return dependencies[node]?.iterator() ?: emptyList<RuntimeModuleGroup>().iterator()
    }
  }

  private class RuntimeModuleGroupImpl(
    override val representativeModule: PluginModuleDescriptor,
    override val sortedDescriptors: ArrayList<IdeaPluginDescriptorImpl> = ArrayList(),
  ) : RuntimeModuleGroup

  private class RuntimeModuleGroupGraphImpl(
    override val sortedGroups: List<RuntimeModuleGroup>,
    private val dependencies: HashMap<RuntimeModuleGroup, List<RuntimeModuleGroup>>,
    private val descriptorToGroup: HashMap<IdeaPluginDescriptorImpl, RuntimeModuleGroup>,
  ) : RuntimeModuleGroupGraph {
    private val dependents by lazy {
      val result = dependencies.mapValues { ArrayList<RuntimeModuleGroup>() }
      for ((group, dependencies) in dependencies) {
        for (dependency in dependencies) {
          result[dependency]!!.add(group)
        }
      }
      result
    }

    override fun getRuntimeModuleGroup(resolvedDescriptor: IdeaPluginDescriptorImpl): RuntimeModuleGroup {
      return descriptorToGroup[resolvedDescriptor] ?: throw IllegalArgumentException("unknown descriptor: $resolvedDescriptor")
    }

    override fun getDirectDependencies(group: RuntimeModuleGroup): List<RuntimeModuleGroup> {
      return dependencies[group] ?: throw IllegalArgumentException("unknown group: $group")
    }

    override fun getDirectDependents(group: RuntimeModuleGroup): List<RuntimeModuleGroup> {
      return dependents[group] ?: throw IllegalArgumentException("unknown group: $group")
    }
  }

  private class ResolvedPluginSetImpl(
    /** May contain unresolved plugins */
    override val originalPluginSet: UnambiguousPluginSet,
    override val initContext: PluginInitializationContext,
    override val sortedResolvedDescriptors: Set<IdeaPluginDescriptorImpl>,
    override val runtimeModuleGroupGraph: RuntimeModuleGroupGraph,
    private val exclusions: Map<IdeaPluginDescriptorImpl, DescriptorExclusionReason?>,
    private val resolvedDependencies: Map<IdeaPluginDescriptorImpl, List<IdeaPluginDescriptorImpl>>,
  ) : ResolvedPluginSet {
    private val resolvedDependents by lazy {
      val result = resolvedDependencies.mapValues { ArrayList<IdeaPluginDescriptorImpl>() }
      for ((resolvedDescriptor, dependencies) in resolvedDependencies) {
        for (dependency in dependencies) {
          result[dependency]!!.add(resolvedDescriptor)
        }
      }
      result
    }

    override fun getExclusionReason(descriptor: IdeaPluginDescriptorImpl): DescriptorExclusionReason? {
      require(descriptor in exclusions.keys) { "unknown descriptor: $descriptor" }
      return exclusions[descriptor]
    }

    override fun getDirectResolvedDependencies(resolvedDescriptor: IdeaPluginDescriptorImpl): List<IdeaPluginDescriptorImpl> {
      return resolvedDependencies[resolvedDescriptor] ?: throw IllegalArgumentException("unknown/unresolved descriptor: $resolvedDescriptor")
    }

    override fun getDirectResolvedDependents(resolvedDescriptor: IdeaPluginDescriptorImpl): List<IdeaPluginDescriptorImpl> {
      return resolvedDependents[resolvedDescriptor] ?: throw IllegalArgumentException("unknown/unresolved descriptor: $resolvedDescriptor")
    }
  }
}

/**
 * Lambdas are heavy, so only a minimal set of data is stored
 */
private sealed interface ExclusionListenerData

private class ExcludeDependsDescriptorOnParentExclusion(val dependsDescriptor: DependsSubDescriptor) : ExclusionListenerData
private class ExcludeContentModuleOnPluginExclusion(val contentModule: ContentModuleDescriptor) : ExclusionListenerData
private class ExcludePluginOnRequiredContentModuleExclusion(val plugin: PluginMainDescriptor) : ExclusionListenerData
private class ExcludeDependentDescriptorOnModuleExclusion(val dependentDescriptor: IdeaPluginDescriptorImpl) : ExclusionListenerData

private fun UnambiguousPluginSet.sequenceAllDescriptors(): Sequence<IdeaPluginDescriptorImpl> {
  return sequence {
    for (plugin in plugins) {
      yieldAllDescriptors(plugin)
    }
  }
}