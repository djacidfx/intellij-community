// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.openapi.progress.util;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.Cancellation;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.impl.CoreProgressManager;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.util.ConcurrencyUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.locks.Lock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

public final class ProgressIndicatorUtilBase {
  private static final Logger LOG = Logger.getInstance(ProgressIndicatorUtilBase.class);

  private ProgressIndicatorUtilBase() {
  }

  public static void awaitWithCheckCanceled(@NotNull Lock lock) {
    awaitWithCheckCanceled(() -> lock.tryLock(ConcurrencyUtil.DEFAULT_TIMEOUT_MS, MILLISECONDS));
  }

  public static void awaitWithCheckCanceled(@NotNull ThrowableComputable<Boolean, ? extends Exception> waiter) {
    ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    boolean success = false;
    while (!success) {
      checkCancelledEvenWithPCEDisabled(indicator);
      try {
        success = waiter.compute();
      }
      catch (ProcessCanceledException pce) {
        throw pce;
      }
      catch (Exception e) {
        //noinspection InstanceofCatchParameter
        if (!(e instanceof InterruptedException)) {
          LOG.warn(e);
        }
        throw new ProcessCanceledException(e);
      }
    }
  }

  /** Use when a deadlock is possible otherwise. */
  public static void checkCancelledEvenWithPCEDisabled(@Nullable ProgressIndicator indicator) {
    boolean isNonCancelable = Cancellation.isInNonCancelableSection();
    if (isNonCancelable || indicator == null) {
      ((CoreProgressManager)ProgressManager.getInstance()).runCheckCanceledHooks(indicator);
    }
    if (isNonCancelable) return;
    Cancellation.ensureActive();
    if (indicator == null) return;
    indicator.checkCanceled();              // check for cancellation as usual and run the hooks
    if (indicator.isCanceled()) {           // if a just-canceled indicator or PCE is disabled
      indicator.checkCanceled();            // ... let the just-canceled indicator provide a customized PCE
      throw new ProcessCanceledException(); // ... otherwise PCE is disabled so throw it manually
    }
  }
}
