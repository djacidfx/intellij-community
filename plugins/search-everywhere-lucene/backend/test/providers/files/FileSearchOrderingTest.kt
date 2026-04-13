package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.TestFactory

@TestApplication
class FileSearchOrderingTest : FileSearchTestBase() {


  @TestFactory
  fun `ordering 1 ZoIdAct matches ZoomIdeAction`() : List<DynamicNode> {

    val zeroValueAfterImports = file("goland/intellij-go/impl/testData/quickfixes/local-variable/zeroValueImports-after.go")
    val zoomIdeAction = file("community/platform/platform-impl/src/com/intellij/ide/actions/ZoomIdeAction.kt")
    return indexWith(listOf(zeroValueAfterImports, zoomIdeAction)) { index ->
      index.assertSearch("ZoIdAct") {
        findsWithOrdering(listOf(zoomIdeAction, zeroValueAfterImports))
      }
    }
  }

  @TestFactory
  fun `ordering 2 logo jpg matches `() : List<DynamicNode> {
    val logo = file("/community/platform/platform-tests/testData/ui/jetbrains_logo.jpg")
    val kt = file("/contrib/qodana/core/src/org/jetbrains/qodana/staticAnalysis/inspections/runner/Logo.kt")
    return indexWith(listOf(logo,kt)) { index ->
      index.assertSearch("logo.jpg") {
        findsWithOrdering(listOf(logo, kt))
      }
    }
  }
}