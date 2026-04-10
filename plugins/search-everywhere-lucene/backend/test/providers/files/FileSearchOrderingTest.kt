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
}