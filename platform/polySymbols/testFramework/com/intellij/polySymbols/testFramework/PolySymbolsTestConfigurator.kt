package com.intellij.polySymbols.testFramework

import com.intellij.openapi.Disposable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.CodeInsightTestFixture

fun CodeInsightTestFixture.configure(configurator: PolySymbolsTestConfigurator, disposable: Disposable? = null) {
  configurator.configure(this, disposable)
}

interface PolySymbolsTestConfigurator {
  fun configure(fixture: CodeInsightTestFixture, disposable: Disposable?)

  fun beforeDirectoryComparison(resultsDir: VirtualFile, goldDir: VirtualFile) {
  }
}