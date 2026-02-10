// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.intentions

import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.createSmartPointer
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaBackingFieldSymbol
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.base.util.reformat
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.intentions.KotlinApplicableModCommandAction
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtBackingField
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtFunctionLiteral
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtThisExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType


internal class RevertExplicitBackingFieldsIntention :
    KotlinApplicableModCommandAction<KtBackingField, List<SmartPsiElementPointer<KtNameReferenceExpression>>>(KtBackingField::class) {

    override fun KaSession.prepareContext(element: KtBackingField): List<SmartPsiElementPointer<KtNameReferenceExpression>> {
        val property = element.parent as? KtProperty ?: return emptyList()
        return property.containingKtFile.collectDescendantsOfType<KtNameReferenceExpression>()
            .filter { isReferenceTo(it, property) }
            .map { it.createSmartPointer() }
    }

    override fun invoke(
        actionContext: ActionContext,
        element: KtBackingField,
        elementContext: List<SmartPsiElementPointer<KtNameReferenceExpression>>,
        updater: ModPsiUpdater
    ) {
        val property = element.parent as KtProperty

        renameOccurrences(property, elementContext, updater)

        val backingProperty = createBackingProperty(property, element)
        property.parent.addBefore(backingProperty, property)

        element.replace(createGetter(property))

        property.reformat(canChangeWhiteSpacesOnly = true)
    }

    override fun getFamilyName(): @IntentionFamilyName String = KotlinBundle.message("replace.explicit.backing.field.with.private.property")

    override fun isApplicableByPsi(element: KtBackingField): Boolean = element.parent is KtProperty && element.fieldKeyword != null

    private fun renameOccurrences(
        property: KtProperty,
        context: List<SmartPsiElementPointer<KtNameReferenceExpression>>,
        updater: ModPsiUpdater
    ) {
        val psiFactory = KtPsiFactory(property.project)
        val backingPropertyName = backingName(property)
        context
            .mapNotNull { pointer -> pointer.element?.let { updater.getWritable(it) } }
            .forEach { ref ->
                val parentDotQualified = ref.parent as? KtDotQualifiedExpression
                val replacementText = if (ref.hasLocalDeclaration(backingPropertyName)) "this.$backingPropertyName" else backingPropertyName
                val refToReplace = if (parentDotQualified?.receiverExpression is KtThisExpression) parentDotQualified else ref
                refToReplace.replace(psiFactory.createExpression(replacementText))
            }

    }

    private fun KtNameReferenceExpression.hasLocalDeclaration(name: String): Boolean =
        generateSequence(this.parent) { it.parent }
            .takeWhile { it !is KtClassBody }
            .any { element ->
                when (element) {
                    is KtBlockExpression -> element.statements.any { it is KtProperty && it.name == name }
                    is KtFunctionLiteral -> element.valueParameters.any { it.name == name }
                    else -> false
                }
            }

    private fun KaSession.isReferenceTo(ref: KtNameReferenceExpression, property: KtProperty): Boolean {
        val propertySymbol = property.symbol
        val resolvedSymbol = ref.mainReference.resolveToSymbol()
        if (ref.getReferencedName() != property.name) return false
        return resolvedSymbol == propertySymbol ||
                (resolvedSymbol is KaBackingFieldSymbol && resolvedSymbol.owningProperty == propertySymbol)
    }

    private fun createGetter(element: KtProperty): KtPropertyAccessor {
        val body = "get() = ${backingName(element)}"
        return KtPsiFactory(element.project).createProperty("val x $body").getter!!
    }

    private fun createBackingProperty(property: KtProperty, backingField: KtBackingField): KtProperty {
        return KtPsiFactory(property.project).createProperty(
            modifiers = "private",
            name = backingName(property),
            type = backingField.typeReference?.text,
            initializer = backingField.initializer?.text,
            isVar = false
        )
    }

    private fun backingName(property: KtProperty): String {
        return if (property.nameIdentifier?.text?.startsWith('`') == true) "`_${property.name}`" else "_${property.name}"
    }
}
