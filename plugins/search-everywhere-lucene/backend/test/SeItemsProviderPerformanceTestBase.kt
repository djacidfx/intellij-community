// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.searchEverywhereLucene.backend

import com.intellij.platform.searchEverywhere.SeFilterState
import com.intellij.platform.searchEverywhere.SeItem
import com.intellij.platform.searchEverywhere.SeItemsProvider
import com.intellij.platform.searchEverywhere.SeParams
import com.intellij.testFramework.rules.ProjectModelExtension
import com.intellij.tools.ide.metrics.benchmark.Benchmark.newBenchmark
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

abstract class SeItemsProviderPerformanceTestBase {
  @RegisterExtension
  protected val projectModel: ProjectModelExtension = ProjectModelExtension()

  protected val project get() = projectModel.project

  protected fun benchmarkProvider(
    provider: SeItemsProvider,
    inputQuery: String,
    benchmarkName: String = "Lucene search \"$inputQuery\"",
    expectedMinResults: Int = 1,
    resultCount: Int = 50,
    timeout: Duration = 10.seconds,
  ) = runBlocking {
    val params = SeParams(inputQuery, SeFilterState.Empty)




    newBenchmark(benchmarkName) {
      runBlocking {
        val collector = RecordingCollector(resultCount)
        try {
          withTimeoutOrNull(timeout) {
            provider.collectItems(params, collector)
          }
        }
        catch (e: CollectorLimitReachedException) {
          // Expected when resultCount is reached
        }

        val results = collector.results
        if (results.size < expectedMinResults) {
          throw AssertionError("Provider produced only ${results.size} results, but at least $expectedMinResults were expected for query \"$inputQuery\"")
        }

        println("[DEBUG_LOG] Benchmark: $benchmarkName")
        println("[DEBUG_LOG] Total results: ${results.size}")
        collector.arrivalTimes.take(10).forEachIndexed { index, time ->
          println("[DEBUG_LOG] Result ${index + 1} arrival time: ${TimeUnit.NANOSECONDS.toMillis(time - collector.startTime)} ms")
        }
        if (collector.arrivalTimes.size > 10) {
          val lastTime = collector.arrivalTimes.last()
          println("[DEBUG_LOG] Last result (${collector.arrivalTimes.size}) arrival time: ${TimeUnit.NANOSECONDS.toMillis(lastTime - collector.startTime)} ms")
        }
      }
    }.warmupIterations(1).attempts(3).start()
  }

  private class CollectorLimitReachedException : Exception()

  protected class RecordingCollector(private val limit: Int) : SeItemsProvider.Collector {
    val startTime = System.nanoTime()
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
