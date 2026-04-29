// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileChooser.universal

import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.provider.EelProviderUtil
import com.intellij.util.PlatformIcons
import org.jetbrains.annotations.ApiStatus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributes
import kotlin.io.path.name
import kotlin.streams.asSequence

@ApiStatus.Internal
internal object NioFileChooserUtil {

  fun isHidden(path: Path): Boolean {
    if (EelProviderUtil.getOsFamily(path) == EelOsFamily.Windows) {
      return try {
        val attrs = Files.readAttributes(path, DosFileAttributes::class.java)
        attrs.isHidden
      }
      catch (_: IOException) {
        false
      }
    }
    else {
      val fileName = path.fileName
      return fileName != null && fileName.toString().startsWith(".")
    }
  }

  fun toNioPathSafe(file: VirtualFile): Path? {
    return try {
      file.toNioPath()
    }
    catch (_: UnsupportedOperationException) {
      null
    }
  }

  fun safeGetChildren(directory: Path, showHidden: Boolean, showFiles: Boolean): List<Path> {
    val children = runCatching {
      Files.list(directory).asSequence()
        .filter { runCatching { (showFiles || Files.isDirectory(it)) && (showHidden || !isHidden(it)) }.getOrElse { false } }
        .sortedBy { it.name.lowercase() }.toList()
    }.getOrElse { emptyList() }
    return children
  }

  fun getIcon(path: Path) =
    if (Files.isDirectory(path)) PlatformIcons.FOLDER_ICON
    else FileTypeRegistry.getInstance().getFileTypeByFileName(path.toString()).icon
}
