// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.ui.playback.commands;

import com.intellij.openapi.ui.playback.PlaybackContext;
import com.intellij.openapi.ui.playback.StageInfo;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.concurrency.Promise;
import org.jetbrains.concurrency.Promises;

@ApiStatus.Internal
public final class PushStage extends AbstractCommand {

  public static final String PREFIX = CMD_PREFIX + "startTest";

  public PushStage(String text, int line) {
    super(text, line);
  }

  @Override
  protected @NotNull Promise<Object> _execute(@NotNull PlaybackContext context) {
    String name = getText().substring(PREFIX.length()).trim();
    context.test("Test started: " + name, getLine());
    context.pushStage(new StageInfo(name));
    return Promises.resolvedPromise();
  }

  @Override
  protected boolean isToDumpCommand() {
    return false;
  }
}
