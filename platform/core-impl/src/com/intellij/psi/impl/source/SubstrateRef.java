// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.psi.impl.source;

import com.intellij.extapi.psi.StubBasedPsiElementBase;
import com.intellij.lang.ASTNode;
import com.intellij.lang.FileASTNode;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiInvalidElementAccessException;
import com.intellij.psi.impl.source.tree.SharedImplUtil;
import com.intellij.psi.stubs.PsiFileStub;
import com.intellij.psi.stubs.Stub;
import com.intellij.psi.stubs.StubElement;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The backing reference that connects a {@link StubBasedPsiElementBase} to its underlying
 * data source — an AST node, a stub, or a spine index.
 * <p>
 * A stub-based PSI element does not hold a direct pointer to its AST node or stub. Instead,
 * it delegates to a {@code SubstrateRef} whose concrete type determines how the element
 * resolves its tree data. The substrate can be swapped at runtime (via
 * {@link StubBasedPsiElementBase#setSubstrateRef}) as the file transitions between states:
 *
 * <table>
 *   <caption>Substrate implementations and their lifecycle</caption>
 *   <tr><th>Implementation</th><th>Holds</th><th>When used</th></tr>
 *   <tr>
 *     <td>{@link StubRef}</td>
 *     <td>Direct stub reference</td>
 *     <td>Initial state for elements created from stubs during indexing or first access</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #createAstStrongRef strong AST ref}</td>
 *     <td>Direct AST node reference</td>
 *     <td>After {@link FileTrees#switchToStrongRefs()}, before AST mutation</td>
 *   </tr>
 *   <tr>
 *     <td>{@link SpineRef}</td>
 *     <td>File + spine index</td>
 *     <td>When stubs and AST coexist; survives GC of either tree</td>
 *   </tr>
 *   <tr>
 *     <td>{@link #createInvalidRef invalid ref}</td>
 *     <td>Nothing (throws on access)</td>
 *     <td>After the element has been invalidated (e.g. stub cleared)</td>
 *   </tr>
 * </table>
 *
 * @see StubBasedPsiElementBase#setSubstrateRef
 * @see FileTrees
 * @see SpineRef
 */
@ApiStatus.Internal
public abstract class SubstrateRef {

  /** Returns the AST node for this element, potentially forcing AST loading. */
  public abstract @NotNull ASTNode getNode();

  /**
   * Returns the stub for this element, or {@code null} if the element is currently backed by AST.
   * Delegates to {@link PsiFileImpl#getStubTree()}, which returns {@code null} once the AST is
   * loaded — callers that need stubs even when AST is present should use {@link #getGreenStub()}.
   */
  public @Nullable Stub getStub() {
    return null;
  }

  /**
   * Returns the "green" stub — the stub from a tree that may coexist with a loaded AST —
   * or {@code null} if no stub tree is available at all. Unlike {@link #getStub()}, this does
   * not return {@code null} merely because the AST is loaded. Defaults to {@link #getStub()}.
   */
  public @Nullable Stub getGreenStub() {
    return getStub();
  }

  /** Returns {@code true} if the owning PSI element is still valid (its file has not been invalidated). */
  public abstract boolean isValid();

  /** Returns the {@link PsiFile} containing this element, or throws if the element is invalid. */
  public abstract @NotNull PsiFile getContainingFile();

  /**
   * Creates a substrate that throws {@link PsiInvalidElementAccessException} on any access.
   * Installed by {@link FileTrees#clearStub} when the element's backing tree is discarded.
   */
  static @NotNull SubstrateRef createInvalidRef(@NotNull StubBasedPsiElementBase<?> psi) {
    return new SubstrateRef() {
      @Override
      public @NotNull ASTNode getNode() {
        throw new PsiInvalidElementAccessException(psi);
      }

      @Override
      public boolean isValid() {
        return false;
      }

      @Override
      public @NotNull PsiFile getContainingFile() {
        throw new PsiInvalidElementAccessException(psi);
      }
    };
  }

  /**
   * Creates a substrate that holds a strong reference to an AST node. Installed by
   * {@link FileTrees#switchToStrongRefs()} before AST mutations so that in-place tree
   * edits can update nodes without losing PSI identity.
   */
  public static @NotNull SubstrateRef createAstStrongRef(@NotNull ASTNode node) {
    return new SubstrateRef() {

      @Override
      public @NotNull ASTNode getNode() {
        return node;
      }

      @Override
      public boolean isValid() {
        FileASTNode fileElement = SharedImplUtil.findFileElement(node);
        PsiElement file = fileElement == null ? null : fileElement.getPsi();
        return file != null && file.isValid();
      }

      @Override
      public @NotNull PsiFile getContainingFile() {
        PsiFile file = SharedImplUtil.getContainingFile(node);
        if (file == null) throw PsiInvalidElementAccessException.createByNode(node, null);
        return file;
      }
    };
  }

  /**
   * A substrate backed by a direct, strong reference to a {@link StubElement}.
   * This is the initial substrate for PSI elements created from stubs (e.g. during stub-based
   * resolve or indexing). It does not support {@link #getNode()} — calling it throws
   * {@link UnsupportedOperationException} because AST is not loaded in this state.
   */
  public static class StubRef extends SubstrateRef {
    private final StubElement<?> myStub;

    public StubRef(@NotNull StubElement<?> stub) {
      myStub = stub;
    }

    @Override
    public @NotNull ASTNode getNode() {
      throw new UnsupportedOperationException();
    }

    @Override
    public @NotNull Stub getStub() {
      return myStub;
    }

    @Override
    public boolean isValid() {
      PsiFileStub<?> fileStub = myStub.getContainingFileStub();
      if (fileStub == null) return false;
      PsiFile psi = fileStub.getPsi();
      return psi != null && psi.isValid();
    }

    @Override
    public @NotNull PsiFile getContainingFile() {
      PsiFileStub<?> stub = myStub.getContainingFileStub();
      if (stub == null) {
        throw new PsiInvalidElementAccessException(myStub.getPsi(),
                                                   "stub hierarchy is invalid: " + this + " (" + getClass() + ")" +
                                                   " has null containing file stub", null);
      }
      PsiFile psi = stub.getPsi();
      if (psi != null) {
        return psi;
      }
      return reportFileInvalidError(stub);
    }

    private PsiFile reportFileInvalidError(@NotNull PsiFileStub<?> stub) {
      ApplicationManager.getApplication().assertReadAccessAllowed();

      String reason = stub.getInvalidationReason();
      PsiInvalidElementAccessException exception =
        new PsiInvalidElementAccessException(myStub.getPsi(), "no psi for file stub " + stub + " ("+stub.getClass()+"), invalidation reason=" + reason, null);
      if (PsiFileImpl.STUB_PSI_MISMATCH.equals(reason)) {
        // we're between finding stub-psi mismatch and the next EDT spot where the file is reparsed and stub rebuilt
        //    see com.intellij.psi.impl.source.PsiFileImpl.rebuildStub()
        // most likely it's just another highlighting thread accessing the same PSI concurrently and not yet canceled, so cancel it
        throw new ProcessCanceledException(exception);
      }
      throw exception;
    }
  }
}
