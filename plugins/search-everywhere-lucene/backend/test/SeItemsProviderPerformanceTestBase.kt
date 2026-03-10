// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.searchEverywhereLucene.backend

import com.intellij.platform.searchEverywhere.SeFilterState
import com.intellij.platform.searchEverywhere.SeItem
import com.intellij.platform.searchEverywhere.SeItemsProvider
import com.intellij.platform.searchEverywhere.SeParams
import com.intellij.testFramework.rules.ProjectModelExtension
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class SeItemsProviderPerformanceTestBase {
  @RegisterExtension
  protected val projectModel: ProjectModelExtension = ProjectModelExtension()

  protected val project get() = projectModel.project

  protected fun newBenchmarkForProviders(provider1: SeItemsProvider, provider2: SeItemsProvider) =
    DualProviderBenchmarkBuilder(provider1, provider2)

  protected class DualProviderBenchmarkBuilder(
    private val provider1: SeItemsProvider,
    private val provider2: SeItemsProvider,
  ) {
    private var warmupIterations = 1
    private var runs = 5
    private var query = "a"
    private var resultCount = 50
    private var timeout = 10.seconds

    protected data class BenchmarkResult(
      val p1ClassName: String,
      val p2ClassName: String,
      val p1Runs: List<List<Long>>,
      val p2Runs: List<List<Long>>,
      val p1StartTimes: List<Long>,
      val p2StartTimes: List<Long>,
      val query: String,
      val runs: Int,
    )

    fun warmupIterations(n: Int) = apply { warmupIterations = n }
    fun runs(n: Int) = apply { runs = n }
    fun inputQuery(q: String) = apply { query = q }
    fun resultLimit(n: Int) = apply { resultCount = n }
    fun timeout(t: Duration) = apply { timeout = t }

    private var results: BenchmarkResult? = null

    fun run() = apply {
      runBlocking {
        val params = SeParams(query, SeFilterState.Empty)

        println("[DEBUG_LOG] Starting benchmark for providers (separately)...")
        println("[DEBUG_LOG] Warmup iterations: $warmupIterations")
        repeat(warmupIterations) {
          runProvider(provider1, params, resultCount, timeout)
          runProvider(provider2, params, resultCount, timeout)
        }

        val p1Runs = mutableListOf<List<Long>>()
        val p2Runs = mutableListOf<List<Long>>()
        val p1StartTimes = mutableListOf<Long>()
        val p2StartTimes = mutableListOf<Long>()

        println("[DEBUG_LOG] Running $runs iterations...")
        repeat(runs) {
          val startTime1 = System.nanoTime()
          val collector1 = RecordingCollector(resultCount, startTime1)
          runProvider(provider1, params, resultCount, timeout, collector1)
          p1Runs.add(collector1.arrivalTimes)
          p1StartTimes.add(startTime1)

          val startTime2 = System.nanoTime()
          val collector2 = RecordingCollector(resultCount, startTime2)
          runProvider(provider2, params, resultCount, timeout, collector2)
          p2Runs.add(collector2.arrivalTimes)
          p2StartTimes.add(startTime2)
        }

        results = BenchmarkResult(
          provider1::class.java.simpleName,
          provider2::class.java.simpleName,
          p1Runs, p2Runs,
          p1StartTimes, p2StartTimes,
          query, runs
        )
      }
    }

    fun printResults() = apply {
      val res = results ?: run().results!!
      report(res)
    }

    fun plot() = apply {
      val res = results ?: run().results!!
      drawPlot(res)
    }

    private fun report(res: BenchmarkResult) {
      val p1Firsts = mutableListOf<Double>()
      val p2Firsts = mutableListOf<Double>()
      val p1Lasts = mutableListOf<Double>()
      val p2Lasts = mutableListOf<Double>()
      val p1Intervals = mutableListOf<Double>()
      val p2Intervals = mutableListOf<Double>()

      for (i in 0 until res.runs) {
        val s1 = res.p1StartTimes[i]
        val s2 = res.p2StartTimes[i]
        val a1 = res.p1Runs[i]
        val a2 = res.p2Runs[i]

        if (a1.isNotEmpty()) p1Firsts.add(nsToMs(a1[0] - s1))
        if (a2.isNotEmpty()) p2Firsts.add(nsToMs(a2[0] - s2))

        val minCount = minOf(a1.size, a2.size)
        if (minCount > 0) {
          p1Lasts.add(nsToMs(a1[minCount - 1] - s1))
          p2Lasts.add(nsToMs(a2[minCount - 1] - s2))
        }

        for (j in 1 until a1.size) {
          p1Intervals.add(nsToMs(a1[j] - a1[j - 1]))
        }
        for (j in 1 until a2.size) {
          p2Intervals.add(nsToMs(a2[j] - a2[j - 1]))
        }
      }

      println("[DEBUG_LOG] --- Benchmark Results ---")
      println("[DEBUG_LOG] Query: \"${res.query}\"")
      println("[DEBUG_LOG] Metrics (Mean ± StdDev in ms):")
      printMetric("Time to first result", p1Firsts, p2Firsts, res.p1ClassName, res.p2ClassName)
      printMetric("Time inbetween results", p1Intervals, p2Intervals, res.p1ClassName, res.p2ClassName)
      printMetric("Time to last result", p1Lasts, p2Lasts, res.p1ClassName, res.p2ClassName)

      // Detailed arrival times for the last run
      val lastA1 = res.p1Runs.last()
      val lastA2 = res.p2Runs.last()
      val lastS1 = res.p1StartTimes.last()
      val lastS2 = res.p2StartTimes.last()
      val minResults = minOf(lastA1.size, lastA2.size)

      println("[DEBUG_LOG] Arrival times for the last run (ms):")
      println("[DEBUG_LOG] Index | %-20s | %-20s | Difference".format(res.p1ClassName, res.p2ClassName))
      for (i in 0 until minResults) {
        val t1 = nsToMs(lastA1[i] - lastS1)
        val t2 = nsToMs(lastA2[i] - lastS2)
        println("[DEBUG_LOG] %5d | %20.2f | %20.2f | %10.2f".format(i + 1, t1, t2, t1 - t2))
      }
    }

    private fun drawPlot(res: BenchmarkResult) {
      val lastA1 = res.p1Runs.last()
      val lastA2 = res.p2Runs.last()
      val lastS1 = res.p1StartTimes.last()
      val lastS2 = res.p2StartTimes.last()

      val p1Deltas = lastA1.map { nsToMs(it - lastS1) }
      val p2Deltas = lastA2.map { nsToMs(it - lastS2) }

      if (p1Deltas.isEmpty() && p2Deltas.isEmpty()) {
        println("[DEBUG_LOG] Plot: No results to display")
        return
      }

      val maxResults = maxOf(p1Deltas.size, p2Deltas.size)
      val maxTime = maxOf(p1Deltas.maxOrNull() ?: 0.0, p2Deltas.maxOrNull() ?: 0.0)

      println("[DEBUG_LOG] Plot: Time (y-axis, ms) vs Result Number (x-axis)")
      println("[DEBUG_LOG] Legend: * = ${res.p1ClassName}, + = ${res.p2ClassName}")

      val height = 20
      val width = 60
      val xStep = maxOf(1, maxResults / width)
      val yStep = maxTime / height

      for (h in height downTo 0) {
        val timeThreshold = h * yStep
        val line = StringBuilder("[DEBUG_LOG] %8.2f ms | ".format(timeThreshold))
        for (w in 0 until width) {
          val resultIdx = w * xStep
          if (resultIdx >= maxResults) {
            line.append(" ")
            continue
          }

          var char = " "
          val p1Val = p1Deltas.getOrNull(resultIdx)
          val p2Val = p2Deltas.getOrNull(resultIdx)

          // Very simple heuristic: if the value is roughly at this height level
          val p1Matches = p1Val != null && p1Val >= timeThreshold && (h == height || p1Val < (h + 1) * yStep)
          val p2Matches = p2Val != null && p2Val >= timeThreshold && (h == height || p2Val < (h + 1) * yStep)

          if (p1Matches && p2Matches) char = "X"
          else if (p1Matches) char = "*"
          else if (p2Matches) char = "+"

          line.append(char)
        }
        println(line.toString())
      }
      val axisLine = StringBuilder("[DEBUG_LOG]            +").append("-".repeat(width))
      println(axisLine.toString())
      println("[DEBUG_LOG]            Result Index (xStep=$xStep)")
    }

    private fun nsToMs(ns: Long): Double = ns.toDouble() / 1_000_000.0

    private fun printMetric(name: String, p1Values: List<Double>, p2Values: List<Double>, p1Label: String, p2Label: String) {
      val (m1, s1) = calculateStats(p1Values)
      val (m2, s2) = calculateStats(p2Values)
      println("[DEBUG_LOG] %-25s: %s = %8.2f ± %6.2f | %s = %8.2f ± %6.2f".format(name, p1Label, m1, s1, p2Label, m2, s2))
    }

    private fun calculateStats(values: List<Double>): Pair<Double, Double> {
      if (values.isEmpty()) return 0.0 to 0.0
      val mean = values.average()
      val variance = values.map { (it - mean).let { it * it } }.average()
      return mean to sqrt(variance)
    }

    private suspend fun runProvider(
      provider: SeItemsProvider,
      params: SeParams,
      limit: Int,
      timeout: Duration,
      collector: RecordingCollector = RecordingCollector(limit),
    ) {
      try {
        withTimeoutOrNull(timeout) {
          provider.collectItems(params, collector)
        }
      }
      catch (e: Exception) {
        var cause: Throwable? = e
        while (cause != null && cause !is CollectorLimitReachedException) {
          cause = cause.cause
        }
        if (cause !is CollectorLimitReachedException) {
          throw e
        }
      }
    }
  }

  private class CollectorLimitReachedException : Exception()

  protected class RecordingCollector(private val limit: Int, val startTime: Long = System.nanoTime()) : SeItemsProvider.Collector {
    val arrivalTimes = mutableListOf<Long>()
    val results = mutableListOf<SeItem>()

    override suspend fun put(item: SeItem): Boolean {
      arrivalTimes.add(System.nanoTime())
      results.add(item)
      if (results.size >= limit) {
        throw CollectorLimitReachedException()
      }
      return true
    }
  }
}
