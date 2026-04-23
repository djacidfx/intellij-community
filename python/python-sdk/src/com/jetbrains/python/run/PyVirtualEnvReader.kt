// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:OptIn(LowLevelLocalMachineAccess::class)

package com.jetbrains.python.run

import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.util.EnvironmentUtil
import com.intellij.util.ShellEnvironmentReader
import com.intellij.util.system.LowLevelLocalMachineAccess
import com.jetbrains.python.packaging.PyCondaPackageService
import com.jetbrains.python.sdk.PythonEnvironment
import com.jetbrains.python.sdk.detectPythonEnvironment
import org.jetbrains.annotations.ApiStatus
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.pathString

@ApiStatus.Internal
data class ActivateScript(
  val scriptPath: Path,
  val args: List<String>? = null,
)

internal class PyVirtualEnvReader(private val virtualEnvSdkPath: String) {
  companion object {
    private val LOG = Logger.getInstance(PyVirtualEnvReader::class.java)

    @Suppress("SpellCheckingInspection")
    private val virtualEnvVars = listOf(
      "PATH", "PS1", "VIRTUAL_ENV", "PYTHONHOME", "PROMPT", "_OLD_VIRTUAL_PROMPT",
      "_OLD_VIRTUAL_PYTHONHOME", "_OLD_VIRTUAL_PATH", "CONDA_SHLVL", "CONDA_PROMPT_MODIFIER",
      "CONDA_PREFIX", "CONDA_DEFAULT_ENV",
      "GDAL_DATA", "PROJ_LIB", "JAVA_HOME", "JAVA_LD_LIBRARY_PATH"
    )

    /**
     * Filter envs that are set up by the activate script, adding other variables from the different shell can break the actual shell.
     */
    fun filterVirtualEnvVars(env: Map<String, String>): Map<String, String> {
      return env.filterKeys { k -> virtualEnvVars.any { it.equals(k, true) } }
    }
  }

  private val shell: String? by lazy {
    when {
      Path.of("/bin/bash").exists() -> "/bin/bash"
      Path.of("/bin/sh").exists() -> "/bin/sh"
      else -> System.getenv("SHELL")
    }
  }

  // in case of Conda we need to pass an argument to the activation script telling which environment to activate
  val activate: ActivateScript? = resolveActivateScript(virtualEnvSdkPath, shell)

  fun readPythonEnv(): MutableMap<String, String> {
    val script = activate ?: run {
      LOG.error("Can't find activate script for $virtualEnvSdkPath")
      return mutableMapOf()
    }

    val command = if (script.scriptPath.getEelDescriptor().osFamily == EelOsFamily.Windows) {
      ShellEnvironmentReader.winShellCommand(script.scriptPath, script.args)
    }
    else {
      ShellEnvironmentReader.shellCommand(shell, script.scriptPath, false, script.args)
    }
    command.environment().putAll(EnvironmentUtil.getEnvironmentMap())

    return try {
      ShellEnvironmentReader.readEnvironment(command, 0).first
    }
    catch (e: IOException) {
      LOG.warn("Couldn't read shell environment: ${e.message}")
      mutableMapOf()
    }
  }
}

/**
 * @deprecated Use [resolveActivateScript] which returns [ActivateScript].
 */
@Deprecated("Use resolveActivateScript()", ReplaceWith("resolveActivateScript(shellPath)"))
fun findActivateScript(sdkPath: String?, shellPath: String?): Pair<String, String?>? =
  resolveActivateScript(sdkPath, shellPath)?.let { Pair(it.scriptPath.absolutePathString(), it.args?.firstOrNull()) }

private fun resolveActivateScript(sdkPath: String?, shellPath: String?): ActivateScript? {
  if (sdkPath == null) return null
  val pythonBinaryPath = Path.of(sdkPath)
  val pythonEnvironment = pythonBinaryPath.detectPythonEnvironment().getOr { return null }
  return pythonEnvironment.resolveActivateScript(shellPath)
}

@ApiStatus.Internal
fun PythonEnvironment.resolveActivateScript(shellPath: String?): ActivateScript? = when (this) {
  is PythonEnvironment.Venv -> {
    val shellName = shellPath?.let { Path.of(it).name }
    val activate = findActivateInPath(pythonBinaryPath, shellName)
    if (activate != null && activate.exists()) ActivateScript(activate) else null
  }
  is PythonEnvironment.Conda -> {
    val condaExecutable = condaExecutable ?: PyCondaPackageService.getCondaExecutable() ?: return null
    val activate = condaExecutable.resolveSibling("activate").takeIf { it.exists() }
                   ?: condaExecutable.parent?.parent?.resolve("bin/activate")?.takeIf { it.exists() }

    if (activate != null && activate.exists()) ActivateScript(activate, listOf(pythonHomePath.pathString)) else null
  }
  is PythonEnvironment.SystemPython -> null
}


private fun findActivateInPath(path: Path, shellName: String?): Path? {
  val parent = path.parent ?: return null
  return if (path.getEelDescriptor().osFamily == EelOsFamily.Windows) findActivateOnWindows(parent)
  else if (shellName == "fish" || shellName == "csh") parent.resolve("activate.$shellName")
  else parent.resolve("activate")
}

private fun findActivateOnWindows(path: Path): Path? {
  for (location in arrayListOf("activate.bat", "Scripts/activate.bat")) {
    val file = path.resolveSibling(location)
    if (file.exists()) {
      return file
    }
  }
  return null
}
