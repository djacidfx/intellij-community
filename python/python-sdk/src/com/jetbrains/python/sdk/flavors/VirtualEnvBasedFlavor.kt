// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.sdk.flavors

import org.jetbrains.annotations.ApiStatus

/**
 * Marker for flavors whose SDKs live in a PEP-405 virtual-environment-shaped directory:
 * the binary is in `bin/python` (Posix) or `Scripts/python.exe` (Windows), with `pyvenv.cfg`
 * at the root.
 *
 * Implement on any flavor whose SDK home lives inside a dedicated virtualenv directory that
 * should be excluded from the module's content roots. Used by
 * `com.jetbrains.python.sdk.getInnerVirtualEnvRoot` via `Sdk.isInnerVirtualEnv`.
 *
 * Do NOT implement for conda (handled separately) or for system-wide pythons.
 */
@ApiStatus.Internal
interface VirtualEnvBasedFlavor
