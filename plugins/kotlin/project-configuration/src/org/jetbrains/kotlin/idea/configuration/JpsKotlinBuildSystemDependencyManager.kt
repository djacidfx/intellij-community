// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.configuration

import com.intellij.jarRepository.JarRepositoryManager
import com.intellij.jarRepository.RepositoryLibraryType
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ExternalLibraryDescriptor
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.impl.libraries.LibraryEx
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTable
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.observation.launchTracked
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.idea.maven.utils.library.RepositoryLibraryProperties
import org.jetbrains.kotlin.idea.base.util.isGradleModule
import org.jetbrains.kotlin.idea.base.util.isMavenModule

@ApiStatus.Internal
class JpsKotlinBuildSystemDependencyManager(val coroutineScope: CoroutineScope) : KotlinBuildSystemDependencyManager {
    override fun isApplicable(module: Module): Boolean {
        return !module.isGradleModule && !module.isMavenModule && ExternalSystemApiUtil.getExternalProjectPath(module) == null
    }

    private fun ExternalLibraryDescriptor.suggestNameForLibrary(projectLibraryTable: LibraryTable): String? {
        if (projectLibraryTable.getLibraryByName(presentableName) == null) {
            return presentableName
        }
        for (i in 1..1000) {
            val nameToCheck = "${presentableName}$i"
            if (projectLibraryTable.getLibraryByName(nameToCheck) == null) {
                return nameToCheck
            }
        }
        return null
    }

    private fun Module.addLibrary(library: Library, scope: DependencyScope) {
        with(ModuleRootManager.getInstance(this).modifiableModel) {
            addLibraryEntries(listOf(library), scope, /* exported = */ false)
            commit()
        }
    }

    private suspend fun Project.createNewLibraryAndAddToModule(
        libraryDescriptor: ExternalLibraryDescriptor,
        scope: DependencyScope,
        module: Module
    ): Library? {
        val version = libraryDescriptor.preferredVersion ?: libraryDescriptor.maxVersion ?: libraryDescriptor.minVersion ?: return null
        val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(this)

        val repositoryProperties =
            RepositoryLibraryProperties(
                libraryDescriptor.libraryGroupId,
                libraryDescriptor.libraryArtifactId,
                version,
                /* includeTransitiveDependencies = */ true,
                /* excludedDependencies = */ emptyList()
            )

        val dependencies =
            JarRepositoryManager.loadDependenciesModal(
                /* project = */ this@createNewLibraryAndAddToModule,
                /* libraryProps = */ repositoryProperties,
                /* loadSources = */ true,
                /* loadJavadoc = */ true,
                /* copyTo = */ null,
                /* repositories = */ null
            )

        // Attempt to find a name that is not being used yet

        return writeAction {
            val nameToUse = libraryDescriptor.suggestNameForLibrary(projectLibraryTable) ?: return@writeAction null
            val library = projectLibraryTable.createLibrary(nameToUse) as? LibraryEx ?: return@writeAction null
            library.modifiableModel.apply {
                kind = RepositoryLibraryType.REPOSITORY_LIBRARY_KIND
                properties = repositoryProperties

                dependencies.forEach {
                    addRoot(it.file, it.type)
                }
            }.commit()

            module.addLibrary(library, scope)

            library
        }
    }

    private fun Project.findExistingLibrary(libraryDescriptor: ExternalLibraryDescriptor): Library? {
        val version = libraryDescriptor.preferredVersion ?: return null
        val projectLibraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(this)
        return projectLibraryTable.libraries.filterIsInstance<LibraryEx>().firstOrNull { library ->
            val properties = library.properties as? RepositoryLibraryProperties ?: return@firstOrNull false

            properties.artifactId == libraryDescriptor.libraryArtifactId &&
                    properties.groupId == libraryDescriptor.libraryGroupId &&
                    properties.version == version
        }
    }

    override fun addDependency(module: Module, libraryDescriptor: ExternalLibraryDescriptor): Job {
        val project = module.project
        val scope = libraryDescriptor.preferredScope ?: DependencyScope.COMPILE

        // Allow for reusing the library if it already exists
        return coroutineScope.launchTracked {
            readAction { project.findExistingLibrary(libraryDescriptor) } ?.let {
                writeAction {
                    module.addLibrary(it, scope)
                }
                return@launchTracked
            }

            project.createNewLibraryAndAddToModule(libraryDescriptor, scope, module)
        }
    }

    override fun getBuildScriptFile(module: Module): VirtualFile? {
        // Jps does not have a build script file
        return null
    }

    override fun isProjectSyncInProgress(): Boolean {
        // Jps does not need to sync projects
        return false
    }

    override fun isProjectSyncPending(): Boolean {
        // Jps does not need to sync projects
        return false
    }

    override fun startProjectSync() {
        // Jps does not need to sync projects
    }
}