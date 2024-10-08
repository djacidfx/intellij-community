// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.codeStyle;

import com.intellij.openapi.util.TextRange;
import com.intellij.util.containers.FList;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@ApiStatus.Internal
public abstract class MinusculeMatcherWrapper extends MinusculeMatcher {
  protected final MinusculeMatcher myDelegate;

  protected MinusculeMatcherWrapper(MinusculeMatcher delegate) {
    myDelegate = delegate;
  }

  @Override
  public boolean matches(@NotNull String name) {
    return myDelegate.matches(name);
  }

  @Override
  public int matchingDegree(@NotNull String name, boolean valueStartCaseMatch) {
    return myDelegate.matchingDegree(name, valueStartCaseMatch);
  }

  @Override
  public int matchingDegree(@NotNull String name) {
    return myDelegate.matchingDegree(name);
  }

  @Override
  public boolean isStartMatch(@NotNull String name) {
    return myDelegate.isStartMatch(name);
  }

  @Override
  public @NotNull String getPattern() {
    return myDelegate.getPattern();
  }

  @Override
  public FList<TextRange> matchingFragments(@NotNull String name) {
    return myDelegate.matchingFragments(name);
  }

  @Override
  public int matchingDegree(@NotNull String name,
                            boolean valueStartCaseMatch,
                            @Nullable FList<? extends TextRange> fragments) {
    return myDelegate.matchingDegree(name, valueStartCaseMatch, fragments);
  }
}
