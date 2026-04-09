// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.gist;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.io.DataExternalizer;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.io.DataInput;

/**
 * An externalizer to store and retrieve gist data that wants to know where the gist data file is located. 
 * 
 * @param <T> type of values stored in the gist.
 */
@ApiStatus.Internal
public interface VirtualFileAwareExternalizer<T> extends DataExternalizer<T> {
  /**
   * @throws UnsupportedOperationException, as {@link VirtualFile} is not supplied.
   */
  @Override
  default T read(@NotNull DataInput in) throws UnsupportedOperationException {
    throw new UnsupportedOperationException("Call read(file, in) instead, supplying the original VirtualFile that stores the gist");
  }

  /**
   * Reads the record from the gist {@link DataInput}
   * 
   * @param file virtual file that stores gist data
   * @param in data input positioned to the beginning of the record
   * @return deserialized value from the input
   */
  T read(@NotNull VirtualFile file, @NotNull DataInput in);
}
