// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("EelProviderUtil")
package com.intellij.platform.eel.provider

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.runBlockingMaybeCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.platform.core.nio.fs.MultiRoutingFsPath
import com.intellij.platform.eel.EelApi
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.EelMachine
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.EelPlatform
import com.intellij.platform.eel.EelPosixApi
import com.intellij.platform.eel.EelWindowsApi
import com.intellij.platform.eel.LocalEelApi
import com.intellij.platform.eel.annotations.MultiRoutingFileSystemPath
import com.intellij.util.system.LowLevelLocalMachineAccess
import com.intellij.util.system.OS
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path

private val logger = logger<LocalEelMachine>()

@ApiStatus.Experimental
interface LocalWindowsEelApi : LocalEelApi, EelWindowsApi

@ApiStatus.Experimental
interface LocalPosixEelApi : LocalEelApi, EelPosixApi

private val EEL_MACHINE_KEY: Key<EelMachine> = Key.create("com.intellij.platform.eel.machine")
private val EEL_DESCRIPTOR_KEY: Key<EelDescriptor> = Key.create("com.intellij.platform.eel.descriptor")

fun Project.getEelMachine(): EelMachine {
  val descriptor = getEelDescriptor()

  if (descriptor is LocalEelDescriptor) {
    return LocalEelMachine
  }

  val cachedEelMachine = getUserData(EEL_MACHINE_KEY)

  if (cachedEelMachine != null) {
    return cachedEelMachine
  }
  else {
    val resolvedEelMachine = descriptor.getResolvedEelMachine()

    if (resolvedEelMachine != null) {
      logger.error("EelMachine is not initialized for project: $this. Using resolved EelMachine: $resolvedEelMachine")
      return resolvedEelMachine
    }

    error("Cannot find EelMachine for project: $this.")
  }
}

@ApiStatus.Internal
fun Project.setEelMachine(machine: EelMachine) {
  putUserData(EEL_MACHINE_KEY, machine)
}

@ApiStatus.Experimental
fun Path.getEelDescriptor(): EelDescriptor {
  val fs = when (this) {
    is MultiRoutingFsPath -> currentDelegate.fileSystem
    else -> fileSystem
  }
  if (fs is EelDescriptorOwner) {
    return fs.eelDescriptor
  }
  return LocalEelDescriptor
}

@get:ApiStatus.Experimental
val Path.osFamily: EelOsFamily get() = getEelDescriptor().osFamily

/**
 * NIO Path compatibility extension for [EelMachine.ownsDescriptor].
 * Resolves the [EelDescriptor] from the NIO path and delegates.
 */
@ApiStatus.Experimental
fun EelMachine.ownsPath(path: Path): Boolean = ownsDescriptor(path.getEelDescriptor())

/**
 * Retrieves [EelDescriptor] for the environment where [this] is located.
 * If the project is not the real one (i.e., it is default or not backed by a real file), then [LocalEelDescriptor] will be returned,
 * unless an explicit descriptor has been set via [setEelDescriptor] (e.g., for RD thin client with a fake project).
 */
@ApiStatus.Experimental
fun Project.getEelDescriptor(): EelDescriptor {
  getUserData(EEL_DESCRIPTOR_KEY)?.let { return it }

  @MultiRoutingFileSystemPath
  val filePath = projectFilePath
  if (filePath == null) {
    // The path to project file can be null if the project is default or used in tests.
    // While the latter is acceptable, the former can give rise to problems:
    // It is possible to "preconfigure" some settings for projects, such as default SDK or libraries.
    // This preconfiguration appears to be tricky in case of non-local projects: it would require UI changes if we want to configure WSL,
    // and in the case of Docker it is simply impossible to preconfigure a container with UI.
    // So we shall limit this preconfiguration to local projects only, which implies that the default project will be associated with the local eel descriptor.
    return LocalEelDescriptor
  }
  return Path.of(filePath).getEelDescriptor()
}

/**
 * Explicitly associates an [EelDescriptor] with this project.
 * This is useful for projects that are not backed by a real file path (e.g., the default project in RD thin client),
 * where the descriptor cannot be inferred from the project file path.
 */
@ApiStatus.Internal
fun Project.setEelDescriptor(descriptor: EelDescriptor) {
  putUserData(EEL_DESCRIPTOR_KEY, descriptor)
}

@get:ApiStatus.Experimental
@OptIn(LowLevelLocalMachineAccess::class)
val localEel: LocalEelApi by lazy {
  if (OS.CURRENT == OS.Windows) ApplicationManager.getApplication().service<LocalWindowsEelApi>()
  else ApplicationManager.getApplication().service<LocalPosixEelApi>()
}

@ApiStatus.Experimental
fun EelMachine.toEelApiBlocking(descriptor: EelDescriptor): EelApi = runBlockingMaybeCancellable { toEelApi(descriptor) }

@ApiStatus.Experimental
fun EelDescriptor.toEelApiBlocking(): EelApi {
  if (this === LocalEelDescriptor) return localEel
  return runBlockingMaybeCancellable { toEelApi() }
}

@ApiStatus.Experimental
data object LocalEelMachine : EelMachine {
  override val internalName: String = "Local"

  override suspend fun toEelApi(descriptor: EelDescriptor): EelApi {
    check(descriptor === LocalEelDescriptor) { "Wrong descriptor: $descriptor for machine: $this" }
    return localEel
  }

  override fun ownsPath(path: Path): Boolean = path.getEelDescriptor() === LocalEelDescriptor
}

@ApiStatus.Experimental
@OptIn(LowLevelLocalMachineAccess::class)
data object LocalEelDescriptor : EelDescriptor {
  private val LOG = logger<LocalEelDescriptor>()

  override val name: String = "Local: ${System.getProperty("os.name")}"

  override val osFamily: EelOsFamily by lazy {
    when (OS.CURRENT) {
      OS.Windows -> EelOsFamily.Windows
      OS.macOS, OS.Linux, OS.FreeBSD -> EelOsFamily.Posix
      else -> {
        LOG.info("Eel is not supported on current platform")
        EelOsFamily.Posix
      }
    }
  }
}


@ApiStatus.Internal
fun EelApi.systemOs(): OS {
  return when (platform) {
    is EelPlatform.Linux -> OS.Linux
    is EelPlatform.Darwin -> OS.macOS
    is EelPlatform.Windows -> OS.Windows
    is EelPlatform.FreeBSD -> OS.FreeBSD
  }
}
