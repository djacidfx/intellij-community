package com.jetbrains.python.psi.search;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.ProjectScope;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.sdk.PyRichSdk;
import com.jetbrains.python.sdk.PythonEnvironment;
import com.jetbrains.python.sdk.legacy.PythonSdkUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

public class PySearchUtilBase {

  /**
   * Creates a scope most suitable for suggesting symbols and files to a user, i.e. in auto-importing or "extended" completion.
   * <p>
   * This scope covers the project's own sources and its libraries, but excludes
   * <ul>
   *   <li>Standard library tests</li>
   *   <li>Stubs for third-party packages in Typeshed</li>
   *   <li>Bundled tests of third-party packages</li>
   *   <li>Bundled dependencies of third-party packages</li>
   * </ul>
   *
   * @param anchor element to detect the corresponding Python SDK
   * @see PySearchScopeBuilder
   */
  public static @NotNull GlobalSearchScope defaultSuggestionScope(@NotNull PsiElement anchor) {
    return PySearchScopeBuilder.forPythonSdkOf(anchor)
      .excludeStandardLibraryTests()
      .excludeThirdPartyPackageTests()
      .excludeThirdPartyPackageBundledDependencies()
      .build();
  }

  /**
   * Calculates a search scope which excludes Python standard library tests. Using such scope may be quite a bit slower than using
   * the regular "project and libraries" search scope, so it should be used only for displaying the list of variants to the user
   * (for example, for class name completion or auto-import).
   *
   * @param project the project for which the scope should be calculated
   * @return the resulting scope
   */
  public static @NotNull GlobalSearchScope excludeSdkTestsScope(@NotNull Project project) {
    return excludeSdkTestScope(ProjectScope.getAllScope(project));
  }

  public static @NotNull GlobalSearchScope excludeSdkTestScope(@NotNull GlobalSearchScope scope) {
    Project project = Objects.requireNonNull(scope.getProject());
    Sdk sdk = ProjectRootManager.getInstance(project).getProjectSdk();
    // TODO cache the scope in project userdata (update when SDK paths change or different project SDK is selected)
    if (sdk != null && PythonSdkUtil.isPythonSdk(sdk)) {
      return scope.intersectWith(PySearchScopeBuilder.forPythonSdk(project, sdk)
                                   .excludeStandardLibraryTests()
                                   .build());
    }
    return scope;
  }

  public static @Nullable VirtualFile findLibDir(@NotNull Sdk sdk) {
    return findLibDir(ReadAction.compute(() -> sdk.getRootProvider().getFiles(OrderRootType.CLASSES)));
  }

  public static @Nullable VirtualFile findVirtualEnvLibDir(@NotNull PyRichSdk<?> rich) {
    if (!(rich.getPythonEnvironment() instanceof PythonEnvironment.Venv venv)) return null;

    Path libRoot = venv.getLibRoot();

    Sdk sdk = rich.getSdk();
    VirtualFile[] classVFiles = ReadAction.compute(() -> sdk.getRootProvider().getFiles(OrderRootType.CLASSES));
    // Empty in case of a temporary empty SDK created to install package management
    if (classVFiles.length == 0) {
      return LocalFileSystem.getInstance().findFileByNioFile(libRoot);
    }

    for (VirtualFile file : classVFiles) {
      if (file.toNioPath().equals(libRoot)) {
        return file;
      }
      // venv module doesn't add virtualenv's lib/pythonX.Y directory itself in sys.path
      VirtualFile parent = file.getParent();
      if (PyNames.SITE_PACKAGES.equals(file.getName()) && parent != null && parent.toNioPath().equals(libRoot)) {
        return parent;
      }
    }
    return null;
  }

  private static @Nullable VirtualFile findLibDir(VirtualFile[] files) {
    for (VirtualFile file : files) {
      if (!file.isValid()) {
        continue;
      }
      if ((file.findChild("__future__.py") != null || file.findChild("__future__.pyc") != null) &&
          file.findChild("xml") != null && file.findChild("email") != null) {
        return file;
      }
      // Mock SDK does not have aforementioned modules
      if (ApplicationManager.getApplication().isUnitTestMode() && file.getName().equals("Lib")) {
        return file;
      }
    }
    return null;
  }
}
