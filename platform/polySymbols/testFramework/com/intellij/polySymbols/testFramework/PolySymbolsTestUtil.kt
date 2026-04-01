@file:JvmName("PolySymbolsTestUtil")

package com.intellij.polySymbols.testFramework

import com.intellij.application.options.CodeStyle
import com.intellij.find.findUsages.CustomUsageSearcher
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.find.usages.impl.symbolSearchTarget
import com.intellij.openapi.util.ProperTextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.psi.search.SearchRequestCollector
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.SearchSession
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.CommonProcessors.CollectProcessor
import java.io.File
import kotlin.math.max
import kotlin.math.min

@JvmOverloads
fun CodeInsightTestFixture.usagesAtCaret(
  scope: SearchScope? = null,
  usagesFilter: (usageInfo: UsageInfo, target: PsiElement) -> Boolean = { _, _ -> true },
): List<String> {
  val usages = polySymbolAtCaret()
                 ?.let { symbolSearchTarget(project, it) }
                 ?.let { findUsages(it) }
                 ?.mapNotNull { (it as? UsageInfo2UsageAdapter)?.usageInfo }
                 ?.asSequence()
               ?: elementAtCaret.let { target ->
                 (this as CodeInsightTestFixtureImpl).findUsages(target, scope)
                   .asSequence()
                   .filter { usagesFilter(it, target) }
               }
  return usages.dumpToString()
}

@JvmOverloads
fun CodeInsightTestFixture.fileUsages(
  scope: SearchScope? = null,
  usagesFilter: (usageInfo: UsageInfo, target: PsiElement) -> Boolean = { _, _ -> true },
): List<String> {
  val usageInfos = ArrayList<UsageInfo>()

  for (usageInfo in (this as CodeInsightTestFixtureImpl).findUsages(file, scope)) {
    usageInfos.add(usageInfo)
  }

  val usageCollector = CollectProcessor<Usage>()
  val options = FindUsagesOptions(project).apply {
    isUsages = true
    fastTrack = SearchRequestCollector(SearchSession(file))
    if (scope != null) {
      searchScope = scope
    }
  }

  for (searcher in CustomUsageSearcher.EP_NAME.extensionList) {
    searcher.processElementUsages(file, usageCollector, options)
  }

  for (usage in usageCollector.results) {
    if (usage is UsageInfo2UsageAdapter) {
      usageInfos.add(usage.usageInfo)
    }
  }

  return usageInfos
    .asSequence()
    .filter { usagesFilter(it, file) }
    .dumpToString()
}

private fun Sequence<UsageInfo>.dumpToString(): List<String> {
  return map { usage: UsageInfo ->
    "<" + usage.file!!.name +
    ":" + usage.element!!.textRange +
    ":" + usage.rangeInElement +
    (if (usage.isNonCodeUsage()) ":non-code" else "") +
    ">\t" + getElementText(usage.element!!, usage.rangeInElement)
  }
    .sorted()
    .toList()
}

fun CodeInsightTestFixture.checkUsages(
  signature: String,
  goldFileName: String,
  usagesFilter: (usageInfo: UsageInfo, target: PsiElement) -> Boolean = { _, _ -> true },
  strict: Boolean = true,
  scope: SearchScope? = null,
) {

  moveToOffsetBySignature(signature)

  val checkFileName = "gold/${goldFileName}.txt"
  FileUtil.createIfDoesntExist(File("$testDataPath/$checkFileName"))

  val usages = usagesAtCaret(scope, usagesFilter)
  checkListByFile(usages.sorted(), checkFileName, !strict)
}

fun CodeInsightTestFixture.checkFileUsages(
  goldFileName: String,
  usagesFilter: (usageInfo: UsageInfo, target: PsiElement) -> Boolean = { _, _ -> true },
  strict: Boolean = true,
  scope: SearchScope? = null,
) {
  val checkFileName = "gold/${goldFileName}.txt"
  FileUtil.createIfDoesntExist(File("$testDataPath/$checkFileName"))

  val usages = fileUsages(scope, usagesFilter)
  checkListByFile(usages.sorted(), checkFileName, !strict)
}

private fun getElementText(element: PsiElement, rangeInElement: ProperTextRange?): String {
  if (element is PsiFile && rangeInElement != null) {
    val text = element.text
    return text
      .substring(max(0, rangeInElement.startOffset), min(rangeInElement.endOffset, text.length))
      .replace("\n", "\\n")
  }

  //if (element is XmlTag) {
  //  return element.name
  //}
  //else if (element is XmlAttribute) {
  //  return element.getText()
  //}
  return (element.parent.text.takeIf { it.length < 80 } ?: element.text)
    .replace("\n", "\\n")
}


fun CodeInsightTestFixture.testWithTempCodeStyleSettings(
  consumer: (CodeStyleSettings) -> Unit,
) {
  val manager = CodeStyleSettingsManager.getInstance(project)
  val currSettings = manager.getCurrentSettings()
  val clone = CodeStyle.createTestSettings(currSettings)
  manager.setTemporarySettings(clone)
  try {
    consumer(clone)
  }
  finally {
    manager.dropTemporarySettings()
  }
}