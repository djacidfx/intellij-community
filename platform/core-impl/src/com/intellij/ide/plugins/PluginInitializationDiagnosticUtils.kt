// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.ide.plugins

import com.intellij.ide.plugins.PluginManagerCore.logger
import com.intellij.ide.plugins.PluginManagerCore.oldPluginSetBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
object PluginInitializationDiagnosticUtils {
  fun runNewPluginSetDiagnosticsIfNeeded(
    initContext: PluginInitializationContext,
    pluginsToLoad: UnambiguousPluginSet,
    incompletePlugins: HashMap<PluginId, PluginMainDescriptor>,
    idMap: Map<PluginId, PluginModuleDescriptor>,
    fullIdMap: Map<PluginId, PluginModuleDescriptor>,
    fullContentModuleIdMap: Map<PluginModuleId, ContentModuleDescriptor>,
    pluginNonLoadReasons: MutableMap<PluginId, PluginNonLoadReason>,
    adaptedPluginSet: PluginSet,
  ) {
    if (System.getProperty("psr.diff") != "true" && System.getProperty("psr.bisect") == null) {
      return
    }
    val oldLoadingErrors = ArrayList<PluginNonLoadReason>()
    val (oldSet, _) = oldPluginSetBuilder(
      initContext,
      pluginsToLoad,
      incompletePlugins,
      idMap,
      fullIdMap,
      fullContentModuleIdMap,
      pluginNonLoadReasons,
      oldLoadingErrors::add,
    )

    val psrBisect = System.getProperty("psr.bisect")
    if (psrBisect != null) {
      val bisectSequence = buildBisectSequence(
        newOrder = adaptedPluginSet.sequenceResolvedSortedDescriptorsForRegistration().toList(),
        oldOrder = oldSet.sequenceResolvedSortedDescriptorsForRegistration().toList(),
        psrBisect = psrBisect,
      )
      adaptedPluginSet.descriptorsSequenceForRegistrationInBisectMode = bisectSequence
    }

    if (System.getProperty("psr.diff") == "true") {
      logger.warn("========= Plugin Set Resolution Diff =========")
      val (oldDescriptorRegistrationOrder, newDescriptorRegistrationOrder) = oldSet.sequenceResolvedSortedDescriptorsForRegistration()
        .toList() to adaptedPluginSet.sequenceResolvedSortedDescriptorsForRegistration().toList()
      val oldDescriptorsSet = oldDescriptorRegistrationOrder.toSet()
      val newDescriptorsSet = newDescriptorRegistrationOrder.toSet()
      if (oldDescriptorsSet != newDescriptorsSet) {
        (oldDescriptorsSet - newDescriptorsSet).takeIf { it.isNotEmpty() }?.let {
          logger.warn("!!! Old descriptors that are excluded by new resolver:\n" + it.joinToString("\n") + "\n")
        }
        (newDescriptorsSet - oldDescriptorsSet).takeIf { it.isNotEmpty() }?.let {
          logger.warn("!!! New descriptors that are included by new resolver:\n" + it.joinToString("\n") + "\n")
        }
      }
      if (oldDescriptorRegistrationOrder != newDescriptorRegistrationOrder) {
        logger.warn("!!! Old descriptor registration order:\n" + oldDescriptorRegistrationOrder.joinToString("\n") + "\n")
        logger.warn("!!! New descriptor registration order:\n" + newDescriptorRegistrationOrder.joinToString("\n") + "\n")
      }
      else {
        logger.warn("Enabled modules are identical")
      }

      val newClassloaderConfOrder = adaptedPluginSet.getModulesOrderedForClassLoaderConfiguration().toList()
      val oldClassloaderOrder = oldSet.getModulesOrderedForClassLoaderConfiguration().toList()
      if (newClassloaderConfOrder != oldClassloaderOrder) {
        logger.warn("!!! Old classloader configuration order:\n" + oldClassloaderOrder.joinToString("\n") + "\n")
        logger.warn("!!! New classloader configuration order:\n" + newClassloaderConfOrder.joinToString("\n") + "\n")
      }
      else {
        logger.warn("Classloader configuration order is identical")
      }
      logger.warn("==============================================")
    }
  }

  private fun buildBisectSequence(
    newOrder: List<IdeaPluginDescriptorImpl>,
    oldOrder: List<IdeaPluginDescriptorImpl>,
    psrBisect: String,
  ): Sequence<IdeaPluginDescriptorImpl> {
    val logger = Logger.getInstance(PluginSet::class.java)
    logger.warn("!!!! BISECT MODE !!!! provide -Dpsr.bisect={sequence of 0 and 1 symbols: 0 - test fails, 1 - test succeeds, start with an empty sequence; e.g. 000101}")
    logger.warn("!!!! psr.bisect=$psrBisect")
    check(newOrder.toSet() == oldOrder.toSet()) { "New order and old order must have the same set of plugins" }
    val descriptorToExpectedIndex = HashMap<IdeaPluginDescriptorImpl, Int>()
    val indexToDescriptor = HashMap<Int, IdeaPluginDescriptorImpl>()
    for ((index, descriptor) in oldOrder.withIndex()) {
      descriptorToExpectedIndex[descriptor] = index
      indexToDescriptor[index] = descriptor
    }

    val initialOrder = newOrder.mapTo(ArrayList()) { descriptorToExpectedIndex[it]!! }
    val bisectOrder = ArrayList(initialOrder)

    val totalInvs = bubbleSort(ArrayList(initialOrder)).first
    logger.warn("!!!! total inversions count: $totalInvs")

    var L = 0 // fails
    var R = totalInvs // succeeds
    for (result in psrBisect) {
      val mid = (L + R) / 2
      when (result) {
        '0' -> L = mid
        '1' -> R = mid
        else -> error("Unknown bisect result, use only 0 or 1 symbols: $result")
      }
    }
    logger.warn("!!!! L position (test still fails at): $L")
    logger.warn("!!!! R position (test still succeeds at): $R")

    val mid = (L + R) / 2
    logger.warn("!!!! building sequence at $mid")
    val (invs, lastSwapPos) = bubbleSort(bisectOrder, mid)
    if (invs != mid) {
      logger.warn("!!!! ERROR: applied inversions count is not equal to expected count: $invs")
    }
    val (d1, d2) = indexToDescriptor[bisectOrder[lastSwapPos]] to indexToDescriptor[bisectOrder[lastSwapPos + 1]]
    logger.warn("!!!! last inversion:\n  $d2\n  was put after\n  $d1")
    val nextInversionOrder = ArrayList(initialOrder)
    val (_, nextSwapPos) = bubbleSort(nextInversionOrder, mid + 1)
    val (n1, n2) = indexToDescriptor[nextInversionOrder[nextSwapPos]] to indexToDescriptor[nextInversionOrder[nextSwapPos + 1]]
    logger.warn("!!!! next inversion would be:\n  put $n2\n  after $n1")
    val bisectSequence = sequence {
      for (index in bisectOrder) {
        yield(indexToDescriptor[index]!!)
      }
    }
    if (System.getProperty("psr.bisect.show.sequence") == "true") {
      logger.warn("!!!! running sequence:\n${bisectSequence.joinToString("\n")}\n\n!!!!")
    }
    return bisectSequence
  }

  /**
   * @return (inversion count, last swap index)
   */
  private fun bubbleSort(array: ArrayList<Int>, targetInversions: Int = -1): Pair<Int, Int> {
    if (targetInversions == 0) return 0 to -1
    val n = array.size
    var invs = 0
    repeat(n) {
      for (i in 0..<(n - 1)){
        if (array[i] > array[i + 1]) {
          val temp = array[i]
          array[i] = array[i + 1]
          array[i + 1] = temp
          invs++
          if (invs == targetInversions) {
            return invs to i
          }
        }
      }
    }
    return invs to -1
  }
}
