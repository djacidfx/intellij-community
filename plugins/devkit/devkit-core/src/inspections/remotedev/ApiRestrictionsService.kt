// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.APP)
@ApiStatus.Internal
class ApiRestrictionsService(private val coroutineScope: CoroutineScope) {

  companion object {
    private val LOG: Logger = logger<ApiRestrictionsService>()
    private const val API_RESTRICTIONS_FILE_PATH = "/inspectionData/ApiRestrictions.json"
    private const val DEPENDENCY_RESTRICTIONS_FILE_PATH = "/inspectionData/DependencyRestrictions.json"

    @JvmStatic
    fun getInstance(): ApiRestrictionsService = service()
  }

  private enum class LoadingState {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED
  }

  enum class ModuleKind(val id: String, val presentableName: String) {
    FRONTEND("frontend", "frontend"),
    LIKELY_FRONTEND("frontend", "[possibly] frontend"),
    BACKEND("backend", "backend"),
    LIKELY_BACKEND("backend", "[possibly] backend"),
    SHARED("shared", "shared")
  }

  private val loadingState = AtomicReference<LoadingState>(LoadingState.NOT_STARTED)
  private val codeRestrictionsRef = AtomicReference<Map<String, ModuleKind>>(emptyMap())
  private val extensionPointRestrictionsRef = AtomicReference<Map<String, ModuleKind>>(emptyMap())
  private val dependencyRestrictionsRef = AtomicReference<Map<String, ModuleKind>>(emptyMap())

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  fun isLoaded(): Boolean {
    return loadingState.get() == LoadingState.COMPLETED
  }

  fun getCodeApiKind(apiName: String): ModuleKind? {
    return codeRestrictionsRef.get()[apiName]
  }

  fun getExtensionPointKind(extensionPointName: String): ModuleKind? {
    return extensionPointRestrictionsRef.get()[extensionPointName]
  }

  fun getDependencyKind(dependencyName: String): ModuleKind? {
    return dependencyRestrictionsRef.get()[dependencyName]
  }

  fun scheduleLoadRestrictions() {
    if (loadingState.compareAndSet(LoadingState.NOT_STARTED, LoadingState.IN_PROGRESS)) {
      coroutineScope.launch {
        loadRestrictions()
      }
    }
  }

  private suspend fun loadRestrictions() {
    try {
      LOG.info("Loading API restrictions from $API_RESTRICTIONS_FILE_PATH and dependency restrictions from $DEPENDENCY_RESTRICTIONS_FILE_PATH")

      val (apiRestrictions, dependencyRestrictions) = withContext(Dispatchers.IO) {
        val apiRestrictionsData = loadRestrictionsData<ApiRestriction>(API_RESTRICTIONS_FILE_PATH)
        val dependencyRestrictionsData = loadRestrictionsData<DependencyRestriction>(DEPENDENCY_RESTRICTIONS_FILE_PATH)

        buildApiRestrictionsLookup(apiRestrictionsData) to buildDependencyRestrictionsLookup(dependencyRestrictionsData)
      }

      codeRestrictionsRef.set(apiRestrictions.codeRestrictions)
      extensionPointRestrictionsRef.set(apiRestrictions.extensionPointRestrictions)
      dependencyRestrictionsRef.set(dependencyRestrictions)

      LOG.info(
        "Loaded ${apiRestrictions.codeRestrictions.size} API restrictions, " +
        "${apiRestrictions.extensionPointRestrictions.size} extension point restrictions, " +
        "and ${dependencyRestrictions.size} dependency restrictions"
      )
      loadingState.set(LoadingState.COMPLETED)

      restartCodeAnalyzer()

    }
    catch (e: Exception) {
      LOG.error("Failed to load API restrictions", e)
      loadingState.set(LoadingState.COMPLETED)
    }
  }

  private inline fun <reified T> loadRestrictionsData(filePath: String): RestrictionsData<T> {
    val inputStream = ApiRestrictionsService::class.java.getResourceAsStream(filePath)
    if (inputStream == null) {
      LOG.warn("Restrictions file not found: $filePath")
      return RestrictionsData()
    }

    val jsonText = inputStream.bufferedReader().use { it.readText() }
    return json.decodeFromString(jsonText)
  }

  private fun buildApiRestrictionsLookup(data: RestrictionsData<ApiRestriction>): RestrictionsLookup {
    val rawRestrictionsByModuleKind = data.withModuleKinds()
    val codeRestrictions = rawRestrictionsByModuleKind.associate { (moduleKind, restriction) ->
      restriction.apiName to moduleKind
    }
    val extensionPointRestrictions = rawRestrictionsByModuleKind
      .flatMap { (moduleKind, restriction) ->
        restriction.extensionPointNames.asSequence().map { it to moduleKind }
      }.toMap()
    return RestrictionsLookup(codeRestrictions = codeRestrictions, extensionPointRestrictions = extensionPointRestrictions)
  }

  private fun buildDependencyRestrictionsLookup(data: RestrictionsData<DependencyRestriction>): Map<String, ModuleKind> {
    return data.withModuleKinds().associate { (moduleKind, restriction) ->
      restriction.dependencyName to moduleKind
    }
  }

  private fun <T> RestrictionsData<T>.withModuleKinds(): Sequence<Pair<ModuleKind, T>> {
    return sequenceOf(
      frontend.asSequence().map { ModuleKind.FRONTEND to it },
      backend.asSequence().map { ModuleKind.BACKEND to it },
      shared.asSequence().map { ModuleKind.SHARED to it },
    ).flatten()
  }

  private data class RestrictionsLookup(
    val codeRestrictions: Map<String, ModuleKind>,
    val extensionPointRestrictions: Map<String, ModuleKind>,
  )

  private fun restartCodeAnalyzer() {
    try {
      val projectManager = ProjectManager.getInstance()
      for (project in projectManager.openProjects) {
        if (!project.isDisposed) {
          DaemonCodeAnalyzer.getInstance(project).restart("API restrictions loaded")
        }
      }
      LOG.info("Restarted DaemonCodeAnalyzer for all open projects")
    }
    catch (e: Exception) {
      LOG.error("Failed to restart DaemonCodeAnalyzer", e)
    }
  }

  @Serializable
  private data class RestrictionsData<T>(
    @SerialName("frontend")
    val frontend: List<T> = emptyList(),

    @SerialName("backend")
    val backend: List<T> = emptyList(),

    @SerialName("shared")
    val shared: List<T> = emptyList(),
  )

  @Serializable
  private data class ApiRestriction(
    @SerialName("apiName")
    val apiName: String,

    @SerialName("extensionPointNames")
    val extensionPointNames: List<String> = emptyList(),
  )

  @Serializable
  private data class DependencyRestriction(
    @SerialName("dependencyName")
    val dependencyName: String,
  )
}
