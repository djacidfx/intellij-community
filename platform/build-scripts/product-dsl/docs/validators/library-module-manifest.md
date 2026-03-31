# Library Module MANIFEST Validation

Entry point: `LibraryModuleManifestValidator` (`NodeIds.LIBRARY_MODULE_MANIFEST_VALIDATION`).

## Overview

Ensures every library wrapper module (`intellij.libraries.*`) has `resources/META-INF/MANIFEST.mf` with a stable `Automatic-Module-Name` equal to the module name.

## Inputs

- All JPS modules from `ModuleOutputProvider.getAllModules()`.
- Library module classification via `JpsModule.isLibraryModule()`.
- Mode: `updateSuppressions`.

## Rules

- Iterate all JPS modules, not only graph-reachable content modules.
- For each library wrapper module:
  - Resolve the module base directory.
  - Target `resources/META-INF/MANIFEST.mf`.
  - Expected content is exactly `Automatic-Module-Name: <moduleName>` followed by a trailing newline.
- If the manifest is missing, create it.
- If the manifest exists but content differs, overwrite it with the expected content.
- Skip all writes and diffs when `updateSuppressions` is enabled.

## Output

- Creates or updates `resources/META-INF/MANIFEST.mf` files via the file updater.
- Does not emit validation errors.
- Does not publish suppression usage.

## Auto-fix

- Yes. Missing or stale MANIFEST files are written automatically.

## Non-goals

- Validation of upstream dependency manifests.
- Rewriting `.iml` resource-root declarations.

## Related

- [validation-rules.md](../validation-rules.md)
- [library-module.md](library-module.md)
