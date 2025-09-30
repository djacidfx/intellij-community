// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("UnstableApiUsage")

package org.jetbrains.bazel.kotlin.builder.wasmjs

import io.opentelemetry.api.trace.Tracer
import org.jetbrains.bazel.jvm.WorkRequest
import org.jetbrains.bazel.jvm.WorkRequestExecutor
import org.jetbrains.bazel.jvm.WorkRequestReader
import org.jetbrains.bazel.jvm.doReadWorkRequestFromStream
import org.jetbrains.bazel.jvm.processRequests
import kotlin.io.path.Path
import kotlin.io.path.readLines
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import java.io.File
import java.io.InputStream
import java.io.Writer
import java.nio.file.Path
import kotlin.system.exitProcess

internal class WasmJsBuildWorker: WorkRequestExecutor {
  override suspend fun execute(request: WorkRequest, writer: Writer, baseDir: Path, tracer: Tracer): Int {
    val args = when (request.arguments.size) {
      1 -> Path(request.arguments[0].removePrefixStrict("--flagfile="))
      else -> {
        System.err.println("ERROR: must specify an argfile using `--flagfile=` as only argument, got '${request.arguments}'")
        return 3
      }
    }.readLines().normalizeCompilerArgs(baseDir)
    return try {
      K2JSCompiler.main(args.toTypedArray())
      0
    } catch (e: Throwable) {
      e.printStackTrace()
      1
    }
  }

  companion object {
    @JvmStatic
    fun main(startupArgs: Array<String>) {
      if (!startupArgs.contains("--persistent_worker")) {
        System.err.println("Only persistent worker mode is supported")
        exitProcess(3)
      }

      processRequests(
        startupArgs = startupArgs,
        executorFactory = { tracer, scope ->
          WasmJsBuildWorker()
        },
        reader = WorkRequestWithDigestReader(System.`in`),
        serviceName = "kotlin-builder-wasmjs",
      )
    }
  }
}

private class WorkRequestWithDigestReader(
  private val input: InputStream,
) : WorkRequestReader {
  override fun readWorkRequestFromStream(): WorkRequest? {
    return doReadWorkRequestFromStream(
      input = input,
      shouldReadDigest = true,
    )
  }
}

private fun String.removePrefixStrict(prefix: String): String {
  val result = removePrefix(prefix)
  check(result != this) {
    "String must start with $prefix but was: $this"
  }
  return result
}

private fun List<String>.normalizeCompilerArgs(baseDir: Path): List<String> {
  return mapIndexed { index, arg ->
    when {
      arg.startsWith("-Xinclude=") -> "-Xinclude=${baseDir.resolveRelative(arg.removePrefix("-Xinclude="))}"
      getOrNull(index - 1) == "-libraries" -> arg.split(File.pathSeparatorChar).filter { it.isNotBlank() }.joinToString(File.pathSeparator) {
        baseDir.resolveRelative(it)
      }
      else -> arg
    }
  }
}

private fun Path.resolveRelative(path: String): String {
  val candidate = Path(path)
  return when {
    candidate.isAbsolute -> candidate
    else -> resolve(candidate)
  }.normalize().toString()
}
