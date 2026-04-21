// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileChooser.universal

import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.Nls
import java.nio.file.FileSystems
import java.nio.file.Path
import javax.swing.Icon

@ApiStatus.Internal
interface UniversalFileChooserContributor {
  enum class MountStatus {
    Permanent, Mounted, Unmounted
  }

  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<UniversalFileChooserContributor> = ExtensionPointName("com.intellij.universalFileChooserContributor")

    fun findOwner(path: Path): UniversalFileChooserContributor? = EP_NAME.findFirstSafe { ext -> ext.ownsPath(path) }
  }

  @get:Nls(capitalization = Nls.Capitalization.Title)
  val tabTitle: String

  fun getRoots(): List<Path>

  fun ownsPath(path: Path): Boolean

  suspend fun getMountStatus(path: Path): MountStatus = MountStatus.Permanent

  suspend fun mount(path: Path) {}

  suspend fun unmount(path: Path) {}

  fun getVirtualRoots(): List<VirtualRoot> = emptyList()

  suspend fun mountVirtualRoot(virtualRoot: VirtualRoot): Path? = null

  data class VirtualRoot(
    val id: String,
    val presentation: Presentation
  )

  data class Presentation(
    @get:Nls val presentableName: String,
    val icon: Icon? = null
  )

  suspend fun getPresentation(path: Path): Presentation? = null
}

fun getFilteredSystemRoots(predicate: (Path) -> Boolean): List<Path> {
  return FileSystems.getDefault().getRootDirectories().filter(predicate)
}