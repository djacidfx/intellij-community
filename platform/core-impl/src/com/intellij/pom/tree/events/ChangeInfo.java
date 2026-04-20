// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.pom.tree.events;

/**
 * Describes a single child-level change within a {@link TreeChange}: the type of modification
 * ({@link #ADD}, {@link #REMOVED}, {@link #REPLACE}, or {@link #CONTENTS_CHANGED}) that happened
 * to one child of the changed parent node.
 *
 * @see TreeChange#getChangeByChild
 */
public interface ChangeInfo {
  short ADD = 0;
  short REMOVED = 1;
  short REPLACE = 2;
  short CONTENTS_CHANGED = 3;

  int getChangeType();
}
