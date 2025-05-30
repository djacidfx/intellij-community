// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.refactoring.pullUp

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.*
import com.intellij.refactoring.memberPullUp.JavaPullUpHelper
import com.intellij.refactoring.memberPullUp.PullUpData
import com.intellij.refactoring.memberPullUp.PullUpHelper
import com.intellij.refactoring.memberPullUp.PullUpHelperFactory
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.refactoring.createJavaClass
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.psiUtil.parents
import org.jetbrains.kotlin.psi.psiUtil.startOffset

class KotlinPullUpHelperFactory : PullUpHelperFactory {
    private fun PullUpData.toKotlinPullUpData(): KotlinPullUpData? {
        val sourceClass = sourceClass.unwrapped as? KtClassOrObject ?: return null
        val targetClass = targetClass.unwrapped as? PsiNamedElement ?: return null
        val membersToMove = membersToMove
            .mapNotNull { it.toKtDeclarationWrapperAware() }
            .sortedBy { it.startOffset }
        return KotlinPullUpData(sourceClass, targetClass, membersToMove)
    }

    override fun createPullUpHelper(data: PullUpData): PullUpHelper<*> {
        if (!data.sourceClass.isInheritor(data.targetClass, true)) return EmptyPullUpHelper
        data.toKotlinPullUpData()?.let { return KotlinPullUpHelper(data, it) }

        if (data.targetClass.language == KotlinLanguage.INSTANCE && data.sourceClass.language == JavaLanguage.INSTANCE) {
            return JavaToKotlinPostconversionPullUpHelper(data)
        }

        return EmptyPullUpHelper
    }
}

class JavaToKotlinPullUpHelperFactory : PullUpHelperFactory {
    private fun createJavaToKotlinPullUpHelper(data: PullUpData): PullUpHelper<*>? {
        if (!data.sourceClass.isInheritor(data.targetClass, true)) return null
        val dummyTargetClass = createDummyTargetClass(data) ?: return null
        val dataForDelegate = object : PullUpData by data {
            override fun getTargetClass() = dummyTargetClass
        }
        return JavaToKotlinPreconversionPullUpHelper(data, dummyTargetClass, JavaPullUpHelper(dataForDelegate))
    }

    private fun createDummyTargetClass(data: PullUpData): PsiClass? {
        val targetClass = data.targetClass.unwrapped as? KtClass ?: return null

        val project = targetClass.project
        val targetPackage = targetClass.containingKtFile.packageFqName.asString()
        val dummyFile = PsiFileFactory.getInstance(project).createFileFromText(
            "dummy.java",
            JavaFileType.INSTANCE,
            if (targetPackage.isNotEmpty()) "package $targetPackage;\n" else ""
        )
        val elementFactory = PsiElementFactory.getInstance(project)

        val dummyTargetClass = createJavaClass(targetClass, null, ClassKind.CLASS)
        val outerClasses = targetClass.parents.filterIsInstance<KtClassOrObject>().toList().asReversed()

        if (outerClasses.isEmpty()) return dummyFile.add(dummyTargetClass) as PsiClass

        val outerPsiClasses = outerClasses.map {
            val psiClass = elementFactory.createClass(it.name!!)
            if (!(it is KtClass && it.isInner())) {
                psiClass.modifierList!!.setModifierProperty(PsiModifier.STATIC, true)
            }
            psiClass
        }
        return outerPsiClasses.asSequence().drop(1).plus(dummyTargetClass)
            .fold(dummyFile.add(outerPsiClasses.first()), PsiElement::add) as PsiClass
    }

    override fun createPullUpHelper(data: PullUpData): PullUpHelper<*> {
        if (data.sourceClass is KtLightClass) return KotlinPullUpHelperFactory().createPullUpHelper(data)

        createJavaToKotlinPullUpHelper(data)?.let { return it }

        return PullUpHelper.INSTANCE
            .allForLanguage(JavaLanguage.INSTANCE)
            .firstOrNull { it !is JavaToKotlinPullUpHelperFactory }
            ?.createPullUpHelper(data)
            ?: EmptyPullUpHelper
    }
}