// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.gradle.versionCatalog.toml

import com.intellij.gradle.java.toml.findTomlFile
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.references.KotlinPsiReferenceProviderContributor

internal class KotlinGradleTomlVersionCatalogPsiReferenceProviderContributor : KotlinPsiReferenceProviderContributor<KtDotQualifiedExpression> {
    override val elementClass: Class<KtDotQualifiedExpression>
        get() = KtDotQualifiedExpression::class.java

    override val referenceProvider: KotlinPsiReferenceProviderContributor.ReferenceProvider<KtDotQualifiedExpression>
        get() = KotlinPsiReferenceProviderContributor.ReferenceProvider { dotExpression ->
            val tomlFile = when {
                !dotExpression.containingFile.name.endsWith(".gradle.kts") -> null
                !dotExpression.matchesTopmostCatalogReferencePattern() -> null
                else -> {
                    val catalogName = dotExpression.text.substringBefore(".")
                    findTomlFile(dotExpression, catalogName)
                }
            }

            listOfNotNull(tomlFile?.let { KtTomlVersionCatalogReference(dotExpression, it) })
        }
}