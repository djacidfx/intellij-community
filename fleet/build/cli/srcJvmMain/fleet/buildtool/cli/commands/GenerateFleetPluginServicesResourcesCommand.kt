// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package fleet.buildtool.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.multiple as multipleOptions
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import com.google.devtools.ksp.impl.KotlinSymbolProcessing
import com.google.devtools.ksp.processing.KSPConfig
import com.google.devtools.ksp.processing.KSPJvmConfig
import com.google.devtools.ksp.processing.KspGradleLogger
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import kotlinx.coroutines.Dispatchers
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.standalone.disposeGlobalStandaloneApplicationServices
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.ServiceLoader
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.system.exitProcess

private const val PLUGIN_SERVICE_FQN = "fleet.kernel.plugins.Plugin"
private val PLUGIN_SERVICE_RESOURCE = Path.of("META-INF", "services", PLUGIN_SERVICE_FQN)
private val GENERATED_RESOURCE_PATHS = listOf(
  Path.of("entityTypes.txt"),
  PLUGIN_SERVICE_RESOURCE,
)
private const val LOG_PREFIX = "[generate-fleet-plugin-services-resources]"

class GenerateFleetPluginServicesResourcesCommand : CliktCommand(
  name = "generate-fleet-plugin-services-resources",
) {
  private val sources by option("--sources").path().multipleOptions()
  private val classpath by option("--classpath").path().multipleOptions()
  private val processorClasspath by option("--processor-classpath").path().multipleOptions(required = true)
  private val moduleName by option("--module-name").required()
  private val jvmTarget by option("--jvm-target").required()
  private val languageVersion by option("--language-version").required()
  private val apiVersion by option("--api-version").required()
  private val outputDir by option("--output-dir").path().required()

  private val logger: Logger = LoggerFactory.getLogger(this::class.java)

  @OptIn(ExperimentalPathApi::class, KaExperimentalApi::class)
  override fun run() {
    val workDir = Files.createTempDirectory("fleet-plugin-services-ksp").toAbsolutePath().normalize()
    try {
      val normalizedSources = sources.map { it.toAbsolutePath().normalize() }
      val normalizedClasspath = classpath.map { it.toAbsolutePath().normalize() }
      val normalizedProcessorClasspath = processorClasspath.map { it.toAbsolutePath().normalize() }
      val classesOutputDirPath = Files.createDirectories(workDir.resolve("classes"))
      val javaOutputDirPath = Files.createDirectories(workDir.resolve("java"))
      val kotlinOutputDirPath = Files.createDirectories(workDir.resolve("kotlin"))
      val resourceOutputDirPath = Files.createDirectories(workDir.resolve("resources"))
      val cachesDirPath = Files.createDirectories(workDir.resolve("caches"))
      val sourceRootPaths = normalizedSources.map { findSourceRoot(it) }.distinct()
      val commonSourceRootPaths = sourceRootPaths.filter { it.fileName.toString().contains("Common", ignoreCase = true) }
      val jvmSourceRootPaths = sourceRootPaths - commonSourceRootPaths.toSet()
      val javaSourceRootPaths = jvmSourceRootPaths.filter { root ->
        normalizedSources.any { it.startsWith(root) && it.toString().endsWith(".java") }
      }
      val projectBaseDirPath = Path.of("").toAbsolutePath().normalize()
      val kspConfig = buildKspConfig(
        moduleName = moduleName,
        jvmSourceRootPaths = jvmSourceRootPaths,
        commonSourceRootPaths = commonSourceRootPaths,
        javaSourceRootPaths = javaSourceRootPaths,
        normalizedClasspath = normalizedClasspath,
        jvmTarget = jvmTarget,
        languageVersion = languageVersion,
        apiVersion = apiVersion,
        projectBaseDirPath = projectBaseDirPath,
        workDir = workDir,
        cachesDirPath = cachesDirPath,
        classesOutputDirPath = classesOutputDirPath,
        javaOutputDirPath = javaOutputDirPath,
        kotlinOutputDirPath = kotlinOutputDirPath,
        resourceOutputDirPath = resourceOutputDirPath,
      )

      val kspExitCode = executeKsp(kspConfig, normalizedProcessorClasspath)
      check(kspExitCode == 0) {
        "KSP failed with exit code $kspExitCode"
      }
      copyGeneratedResources(resourceOutputDirPath, outputDir.toAbsolutePath().normalize())
      awaitGlobalStandaloneApplicationServicesDisposed(processCode = 0)
    }
    catch (t: Throwable) {
      awaitGlobalStandaloneApplicationServicesDisposed(processCode = 1)
      throw t
    }
    finally {
      try {
        workDir.deleteRecursively()
      }
      catch (t: Throwable) {
        logger.error("${LOG_PREFIX} Failed to delete temporary directory: $workDir ; ${t.message}")
      }
    }
  }

  @OptIn(KaExperimentalApi::class)
  private fun awaitGlobalStandaloneApplicationServicesDisposed(processCode: Int) {
    try {
      disposeGlobalStandaloneApplicationServices()
    }
    catch (t: Throwable) {
      logger.error("${LOG_PREFIX} Failed to dispose global standalone application services: ${t.message}")
    }
  }

  private fun copyGeneratedResources(resourceOutputDirPath: Path, outputDir: Path) {
    outputDir.createDirectories()
    for (relativePath in GENERATED_RESOURCE_PATHS) {
      val source = resourceOutputDirPath.resolve(relativePath)
      val target = outputDir.resolve(relativePath)
      logResourceState("Preparing generated resource", relativePath, source, target)
      if (!source.exists()) {
        logMessage("Skipping missing generated resource: ${source.toDebugString()}")
        continue
      }
      requireRegularFile(source, "Generated resource source is not a regular file")
      target.parent?.createDirectories()
      try {
        source.copyTo(target, overwrite = true)
      }
      catch (t: Throwable) {
        throw IllegalStateException(
          buildString {
            appendLine("Failed to copy generated resource.")
            appendLine("relativePath=$relativePath")
            appendLine("source=${source.toDebugString()}")
            appendLine("target=${target.toDebugString()}")
            appendLine("targetParent=${target.parent?.toDebugString() ?: "<null>"}")
          },
          t,
        )
      }
      requireRegularFile(target, "Generated resource target was not created as a regular file")
      logResourceState("Copied generated resource", relativePath, source, target)
    }
  }

  private fun buildKspConfig(
    moduleName: String,
    jvmSourceRootPaths: List<Path>,
    commonSourceRootPaths: List<Path>,
    javaSourceRootPaths: List<Path>,
    normalizedClasspath: List<Path>,
    jvmTarget: String,
    languageVersion: String,
    apiVersion: String,
    projectBaseDirPath: Path,
    workDir: Path,
    cachesDirPath: Path,
    classesOutputDirPath: Path,
    javaOutputDirPath: Path,
    kotlinOutputDirPath: Path,
    resourceOutputDirPath: Path,
  ): KSPJvmConfig {
    return KSPJvmConfig.Builder().apply {
      this.moduleName = moduleName
      javaSourceRoots = javaSourceRootPaths.map { it.toFile() }
      javaOutputDir = javaOutputDirPath.toFile()
      this.jvmTarget = jvmTarget
      sourceRoots = jvmSourceRootPaths.map { it.toFile() }
      commonSourceRoots = commonSourceRootPaths.map { it.toFile() }
      libraries = normalizedClasspath.map { it.toFile() }
      projectBaseDir = projectBaseDirPath.toFile()
      outputBaseDir = workDir.toFile()
      cachesDir = cachesDirPath.toFile()
      classOutputDir = classesOutputDirPath.toFile()
      kotlinOutputDir = kotlinOutputDirPath.toFile()
      resourceOutputDir = resourceOutputDirPath.toFile()
      this.languageVersion = languageVersion
      this.apiVersion = apiVersion
    }.build()
  }

  private fun executeKsp(config: KSPConfig, processorClasspath: List<Path>): Int {
    val loggingLevel = when (System.getProperty("ksp.logging", "warn").lowercase()) {
      "error" -> KspGradleLogger.LOGGING_LEVEL_ERROR
      "warn", "warning" -> KspGradleLogger.LOGGING_LEVEL_WARN
      "info" -> KspGradleLogger.LOGGING_LEVEL_INFO
      "debug" -> KspGradleLogger.LOGGING_LEVEL_LOGGING
      else -> KspGradleLogger.LOGGING_LEVEL_WARN
    }
    val logger = KspGradleLogger(loggingLevel)
    return URLClassLoader(processorClasspath.map { it.toUri().toURL() }.toTypedArray(), this::class.java.classLoader)
      .use { processorClassloader ->
        val processorProviders = ServiceLoader.load(SymbolProcessorProvider::class.java, processorClassloader).toList()
        KotlinSymbolProcessing(config, processorProviders, logger).execute()
      }.code
  }

  private fun findSourceRoot(source: Path): Path {
    var current: Path? = source.parent
    while (current != null) {
      if (current.fileName?.toString()?.startsWith("src") == true) {
        return current
      }
      current = current.parent
    }
    return source.parent ?: source
  }

  private fun requireRegularFile(path: Path, message: String) {
    if (Files.isRegularFile(path)) return
    throw IllegalStateException("$message: ${path.toDebugString()}")
  }

  private fun logResourceState(message: String, relativePath: Path, source: Path, target: Path) {
    logMessage(
      buildString {
        append(message)
        append(": relativePath=")
        append(relativePath)
        append(", source=")
        append(source.toDebugString())
        append(", target=")
        append(target.toDebugString())
      },
    )
  }

  private fun logMessage(message: String) {
    logger.info(message)
  }

  private fun Path.toDebugString(): String {
    return buildString {
      append(this@toDebugString.toAbsolutePath().normalize())
      append(" [exists=")
      append(Files.exists(this@toDebugString))
      append(", regularFile=")
      append(Files.isRegularFile(this@toDebugString))
      append(", directory=")
      append(Files.isDirectory(this@toDebugString))
      append("]")
    }
  }
}