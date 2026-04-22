// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.fileChooser.universal

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.eel.EelOsFamily
import com.intellij.platform.eel.provider.EelProviderUtil
import org.jetbrains.annotations.ApiStatus
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributes

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
}
