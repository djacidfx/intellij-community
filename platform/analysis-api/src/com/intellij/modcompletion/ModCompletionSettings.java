// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.modcompletion;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.Set;

/**
 * Settings that control which {@link ModCompletionItemProvider}s are active during a completion session.
 */
@NotNullByDefault
@ApiStatus.Experimental
public final class ModCompletionSettings {
  private static final ModCompletionSettings DEFAULT = new ModCompletionSettings(Set.of());

  private final Set<Class<? extends ModCompletionItemProvider>> excludedProviderClasses;

  public ModCompletionSettings(Set<Class<? extends ModCompletionItemProvider>> excludedProviderClasses) {
    this.excludedProviderClasses = excludedProviderClasses;
  }

  /**
   * @return {@code true} if the given provider should participate in completion
   */
  public boolean isProviderEnabled(ModCompletionItemProvider provider) {
    for (Class<? extends ModCompletionItemProvider> excluded : excludedProviderClasses) {
      if (excluded.isInstance(provider)) return false;
    }
    return true;
  }

  public static ModCompletionSettings getDefault() {
    return DEFAULT;
  }
}