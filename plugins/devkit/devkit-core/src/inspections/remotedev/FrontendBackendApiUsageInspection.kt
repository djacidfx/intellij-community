// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.idea.devkit.inspections.remotedev

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.util.SmartList
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.VisibleForTesting
import org.jetbrains.idea.devkit.DevKitBundle
import org.jetbrains.idea.devkit.dom.IdeaPlugin
import org.jetbrains.idea.devkit.inspections.DevKitUastInspectionBase
import org.jetbrains.idea.devkit.module.PluginModuleType
import org.jetbrains.idea.devkit.util.DescriptorUtil
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UField
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UTypeReferenceExpression
import org.jetbrains.uast.UastCallKind
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor

private const val FRONTEND_PLATFORM_MODULE_BASE_NAME = "intellij.platform.frontend"
private const val BACKEND_PLATFORM_MODULE_BASE_NAME = "intellij.platform.backend"

private val likelyFrontendDependencies = setOf<String>()
private val likelyBackendDependencies = setOf("intellij.platform.vcs.impl")

@VisibleForTesting
@ApiStatus.Internal
class FrontendBackendApiUsageInspection : DevKitUastInspectionBase(UClass::class.java, UField::class.java, UMethod::class.java) {

  private val restrictionsService = ApiRestrictionsService.getInstance()

  override fun isAllowed(holder: ProblemsHolder): Boolean {
    if (!super.isAllowed(holder)) return false

    val isRestrictionServiceReady = restrictionsService.isLoaded()
    if (isRestrictionServiceReady) {
      return true
    }
    else {
      restrictionsService.scheduleLoadRestrictions()
      return false
    }
  }

  override fun checkClass(
    aClass: UClass,
    manager: InspectionManager,
    isOnTheFly: Boolean,
  ): Array<out ProblemDescriptor?>? {
    val sourcePsi = aClass.sourcePsi ?: return null
    val moduleType = getOrComputeModuleType(sourcePsi)
    val descriptors = SmartList<ProblemDescriptor>()
    aClass.uastSuperTypes.forEach { superTypeExpression ->
      checkApiUsage(superTypeExpression, moduleType, manager, isOnTheFly, descriptors)
    }

    return if (descriptors.isEmpty()) null else descriptors.toTypedArray()
  }

  override fun checkMethod(
    method: @NotNull UMethod,
    manager: @NotNull InspectionManager,
    isOnTheFly: Boolean,
  ): Array<ProblemDescriptor>? {
    return checkBody(method, manager, isOnTheFly)
  }

  private fun checkBody(
    uElement: @NotNull UElement,
    manager: @NotNull InspectionManager,
    isOnTheFly: Boolean,
  ): Array<ProblemDescriptor>? {
    val sourcePsi = uElement.sourcePsi ?: return null
    val moduleType = getOrComputeModuleType(sourcePsi)
    val descriptors = SmartList<ProblemDescriptor>()

    uElement.accept(object : AbstractUastVisitor() {
      override fun visitCallExpression(node: UCallExpression): Boolean {
        checkApiUsage(node, moduleType, manager, isOnTheFly, descriptors)
        return true
      }

      override fun visitQualifiedReferenceExpression(node: UQualifiedReferenceExpression): Boolean {
        // For a.b.c.d, check left-to-right and stop at the first error
        val sizeBeforeCheck = descriptors.size
        checkApiUsage(node, moduleType, manager, isOnTheFly, descriptors)
        val errorReported = descriptors.size > sizeBeforeCheck

        // If error reported, skip visiting children
        return errorReported
      }

      override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
        checkApiUsage(node, moduleType, manager, isOnTheFly, descriptors)
        return true
      }
    })

    return if (descriptors.isEmpty()) null else descriptors.toTypedArray()
  }

  private fun checkApiUsage(
    expression: UExpression,
    currentModuleType: ApiRestrictionsService.ModuleKind,
    manager: InspectionManager,
    isOnTheFly: Boolean,
    descriptors: MutableList<ProblemDescriptor>,
  ) {
    val qualifiedName = getResolvedFqn(expression) ?: return
    val expectedModuleKind = restrictionsService.getModuleKind(qualifiedName) ?: return

    if (!isModuleAllowed(currentModuleType, expectedModuleKind)) {
      val sourcePsi = expression.sourcePsi ?: return
      val message = DevKitBundle.message(
        "inspection.api.usage.restricted.to.module.type.default.message",
        qualifiedName,
        expectedModuleKind.presentableName
      )

      descriptors.add(
        manager.createProblemDescriptor(
          sourcePsi,
          message,
          isOnTheFly,
          emptyArray<LocalQuickFix>(),
          ProblemHighlightType.WEAK_WARNING
        )
      )
    }
  }

  private fun getResolvedFqn(expression: UExpression): String? {
    return when (expression) {
      is UCallExpression -> {
        if (expression.kind == UastCallKind.CONSTRUCTOR_CALL) {
          PsiTypesUtil.getPsiClass(expression.returnType)?.qualifiedName
        }
        else {
          expression.resolve()?.let { resolved ->
            when {
              resolved is PsiClass -> resolved.qualifiedName
              else -> PsiTypesUtil.getPsiClass((resolved as? UMethod)?.returnType)?.qualifiedName
            }
          }
        }
      }
      is UQualifiedReferenceExpression -> {
        val resolved = expression.resolve()
        when (resolved) {
          is PsiClass -> {
            resolved.qualifiedName
          }
          is PsiMethod -> {
            val uMethod = resolved.toUElementOfType<UMethod>() ?: return null
            val containingClass = uMethod.getContainingUClass() ?: return null
            "${containingClass.qualifiedName}.${uMethod.name}"
          }
          null -> {
            null
          }
          else -> {
            PsiTypesUtil.getPsiClass(expression.getExpressionType())?.qualifiedName
          }
        }
      }
      is USimpleNameReferenceExpression -> {
        val resolved = expression.resolve()
        when (resolved) {
          is PsiClass -> resolved.qualifiedName
          else -> null
        }
      }
      is UTypeReferenceExpression -> {
        expression.getQualifiedName()
      }
      else -> null
    }
  }

  private fun getOrComputeModuleType(element: PsiElement): ApiRestrictionsService.ModuleKind {
    val cacheHolder = element.containingFile ?: return ApiRestrictionsService.ModuleKind.SHARED
    return CachedValuesManager.getCachedValue(cacheHolder) {
      val moduleType = computeModuleType(cacheHolder)
      CachedValueProvider.Result.create(moduleType, PsiModificationTracker.MODIFICATION_COUNT)
    }
  }

  private fun computeModuleType(file: PsiFile): ApiRestrictionsService.ModuleKind {
    val module = ModuleUtilCore.findModuleForFile(file) ?: return ApiRestrictionsService.ModuleKind.SHARED

    // heuristics:
    val moduleName = module.name
    when {
      moduleName.endsWith("frontend") -> return ApiRestrictionsService.ModuleKind.FRONTEND
      moduleName.endsWith("backend") -> return ApiRestrictionsService.ModuleKind.BACKEND
    }

    val pluginXml = PluginModuleType.getPluginXml(module)
                    ?: PluginModuleType.getContentModuleDescriptorXml(module)
                    ?: return ApiRestrictionsService.ModuleKind.SHARED
    val ideaPlugin = DescriptorUtil.getIdeaPlugin(pluginXml) ?: return ApiRestrictionsService.ModuleKind.SHARED

    val allDependencies = collectAllDirectDependencies(ideaPlugin)

    return when {
      isDefinitelyFrontendModule(moduleName, allDependencies) -> ApiRestrictionsService.ModuleKind.FRONTEND
      isDefinitelyBackendModule(moduleName, allDependencies) -> ApiRestrictionsService.ModuleKind.BACKEND
      doesLookLikeFrontendDependencies(allDependencies) -> ApiRestrictionsService.ModuleKind.LIKELY_FRONTEND
      doesLookLikeBackendDependencies(allDependencies) -> ApiRestrictionsService.ModuleKind.LIKELY_BACKEND
      else -> ApiRestrictionsService.ModuleKind.SHARED
    }
  }

  private fun isDefinitelyFrontendModule(moduleName: String, moduleDependencies: Set<String>): Boolean {
    return getModuleNameVariants("frontend", includeSplit = true, includeGradle = true).any { moduleName.endsWith(".$it") }
           || getModuleNameVariants(FRONTEND_PLATFORM_MODULE_BASE_NAME).any { it in moduleDependencies }
  }

  private fun isDefinitelyBackendModule(moduleName: String, moduleDependencies: Set<String>): Boolean {
    return getModuleNameVariants("backend", includeSplit = true, includeGradle = true).any { moduleName.endsWith(".$it") }
           || getModuleNameVariants(BACKEND_PLATFORM_MODULE_BASE_NAME).any { it in moduleDependencies }
  }

  private fun doesLookLikeFrontendDependencies(moduleDependencies: Set<String>): Boolean {
    return moduleDependencies.any { moduleDependency ->
      moduleDependency in likelyFrontendDependencies
      || getModuleNameVariants("frontend").any { moduleDependency.endsWith(".$it") }
    }
  }

  private fun doesLookLikeBackendDependencies(moduleDependencies: Set<String>): Boolean {
    return moduleDependencies.any { moduleDependency ->
      moduleDependency in likelyBackendDependencies
      || getModuleNameVariants("backend").any { moduleDependency.endsWith(".$it") }
    }
  }

  private fun isModuleAllowed(actualKind: ApiRestrictionsService.ModuleKind, expectedKind: ApiRestrictionsService.ModuleKind): Boolean {
    return when {
      expectedKind == ApiRestrictionsService.ModuleKind.SHARED || actualKind == ApiRestrictionsService.ModuleKind.SHARED -> true
      else -> expectedKind.id == actualKind.id
    }
  }

  private fun collectAllDirectDependencies(ideaPlugin: IdeaPlugin): Set<String> {
    val allDependencies = mutableSetOf<String>()

    ideaPlugin.getDepends().forEach { dependency ->
      dependency.rawText?.let { allDependencies.add(it) }
    }

    val dependenciesDescriptor = ideaPlugin.dependencies
    if (dependenciesDescriptor.isValid) {
      dependenciesDescriptor.moduleEntry.forEach { moduleDescriptor ->
        moduleDescriptor.name.stringValue?.let { allDependencies.add(it) }
      }
      dependenciesDescriptor.plugin.forEach { pluginDescriptor ->
        pluginDescriptor.id.stringValue?.let { allDependencies.add(it) }
      }
    }
    return allDependencies
  }

  private fun getModuleNameVariants(
    baseName: String,
    includeSplit: Boolean = true,
    includeGradle: Boolean = true,
  ): Sequence<String> {
    return sequence {
      yield(baseName)
      if (includeSplit) yield("$baseName.split")
      // gradle specific
      if (includeGradle) yield("$baseName.main")
      if (includeSplit && includeGradle) yield("$baseName.split.main")
    }
  }
}
