// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk

import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.jetbrains.python.PythonBinary
import com.jetbrains.python.PythonHomePath
import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.sdk.impl.detectPythonEnvironmentImpl
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path

@ApiStatus.Internal
interface HasPythonHome {
  val pythonHomePath: PythonHomePath
}

/**
 * The kind of Python environment detected from the file system layout.
 */
@ApiStatus.Internal
sealed interface PythonEnvironment {
  val pythonBinaryPath: PythonBinary

  /** Virtual environment with parsed `pyvenv.cfg` contents. */
  data class Venv(
    override val pythonBinaryPath: PythonBinary,
    override val pythonHomePath: PythonHomePath,
    val config: Map<String, String>,
    /** The `lib/` or `lib/pythonX.Y/` directory of the virtual environment. */
    val libRoot: Path,
  ) : PythonEnvironment, HasPythonHome

  /** Conda environment (has `conda-meta` directory). */
  data class Conda(
    override val pythonBinaryPath: PythonBinary,
    override val pythonHomePath: PythonHomePath,
    val condaMetaPath: Path,
    /** `true` if this is the base conda installation (has `condabin/` or `envs/` subdirectory). */
    val isBase: Boolean,
    /** Path to the `conda` executable resolved relative to this environment, or `null` if not found. */
    val condaExecutable: Path? = null,
  ) : PythonEnvironment, HasPythonHome

  /** System/global Python installation. */
  data class SystemPython(
    override val pythonBinaryPath: PythonBinary,
  ) : PythonEnvironment
}

/**
 * Detects the Python environment type from the file system layout around this binary.
 *
 * - If `pyvenv.cfg` exists (PEP 405, Python 3.3+), returns [PythonEnvironment.Venv] with the full parsed config map.
 * - If `bin/activate_this.py` or `Scripts/activate_this.py` exists (legacy `virtualenv` for Python 2.7),
 *   returns [PythonEnvironment.Venv] with an empty config map.
 * - If `conda-meta/` directory exists, returns [PythonEnvironment.Conda] with the resolved conda executable.
 * - Otherwise returns [PythonEnvironment.SystemPython].
 *
 * Returns an error if the binary does not exist or is not executable.
 */
@ApiStatus.Internal
@RequiresBackgroundThread
fun PythonBinary.detectPythonEnvironment(): PyResult<PythonEnvironment> = detectPythonEnvironmentImpl()
