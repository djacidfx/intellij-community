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
    private const val RESTRICTIONS_FILE_PATH = "/inspectionData/ApiRestrictions.json"

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
  private val restrictionsRef = AtomicReference<Map<String, ModuleKind>>(emptyMap())

  private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
  }

  fun isLoaded(): Boolean {
    return loadingState.get() == LoadingState.COMPLETED
  }

  fun getModuleKind(qualifiedName: String): ModuleKind? {
    return restrictionsRef.get()[qualifiedName]
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
      LOG.info("Loading API restrictions from $RESTRICTIONS_FILE_PATH")

      val restrictions = withContext(Dispatchers.IO) {
        val inputStream = ApiRestrictionsService::class.java.getResourceAsStream(RESTRICTIONS_FILE_PATH)
        if (inputStream == null) {
          LOG.warn("API restrictions file not found: $RESTRICTIONS_FILE_PATH")
          return@withContext emptyMap()
        }

        val jsonText = inputStream.bufferedReader().use { it.readText() }
        val data = json.decodeFromString<ApiRestrictionsData>(jsonText)

        buildRestrictionsMap(data)
      }

      restrictionsRef.set(restrictions)

      LOG.info("Loaded ${restrictions.size} API restrictions")
      loadingState.set(LoadingState.COMPLETED)

      restartCodeAnalyzer()

    }
    catch (e: Exception) {
      LOG.error("Failed to load API restrictions", e)
      loadingState.set(LoadingState.COMPLETED)
    }
  }

  private fun buildRestrictionsMap(data: ApiRestrictionsData): Map<String, ModuleKind> {
    val map = mutableMapOf<String, ModuleKind>()

    data.frontend.forEach { fqn ->
      map[fqn] = ModuleKind.FRONTEND
    }

    data.backend.forEach { fqn ->
      map[fqn] = ModuleKind.BACKEND
    }

    data.shared.forEach { fqn ->
      map[fqn] = ModuleKind.SHARED
    }

    return map
  }

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
  private data class ApiRestrictionsData(
    @SerialName("frontend")
    val frontend: List<String> = emptyList(),

    @SerialName("backend")
    val backend: List<String> = emptyList(),

    @SerialName("shared")
    val shared: List<String> = emptyList(),
  )
}
