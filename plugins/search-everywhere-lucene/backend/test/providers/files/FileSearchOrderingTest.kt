package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.TestFactory

@TestApplication
class FileSearchOrderingTest : FileSearchTestBase() {

  fun prefixesOf(str: String) = (1..str.length).map { str.substring(0, it) }

  @TestFactory
  fun `orderings around ZoomIdeAction and ZoomOutAction`() : List<DynamicNode> {

    val zeroValueAfterImports = file("goland/intellij-go/impl/testData/quickfixes/local-variable/zeroValueImports-after.go")
    val zoomIdeAction = file("community/platform/platform-impl/src/com/intellij/ide/actions/ZoomIdeAction.kt")
    val zoomOutAction = file("plugins/graph/srcOpenApi/com/intellij/openapi/graph/builder/actions/ZoomOutAction.java")


    return indexWith(listOf(zeroValueAfterImports, zoomIdeAction,zoomOutAction)) { index ->

      val zoom = prefixesOf("Zoom")
      val ide = prefixesOf("Ide")
      val out = prefixesOf("Out")
      val action = prefixesOf("Action")

      val zoomIdeOrder = listOf(zoomIdeAction, zoomOutAction, zeroValueAfterImports)
      val zoomOutOrder = listOf(zoomOutAction, zoomIdeAction, zeroValueAfterImports)


      index.assertSearch("ZoomIdeAction") {
        explainResults()
        findsAllOf(zoomIdeAction)
        findsWithOrdering(zoomIdeOrder, containsAll = false)
      }

      for (z in zoom) {
        for (a in action) {

          for (i in ide) {
            index.assertSearch(z + i + a) {
              findsAllOf(zoomIdeAction)
              findsWithOrdering(zoomIdeOrder, containsAll = false)
              explainResults()
            }
          }

          for (o in out) {
            index.assertSearch(z + o + a) {
              findsAllOf(zoomOutAction)
              findsWithOrdering(zoomOutOrder, containsAll = false)
            }
          }

        }
      }
    }
  }


  @TestFactory
  fun `ordering 2 logo jpg matches `() : List<DynamicNode> {
    val logo = file("/community/platform/platform-tests/testData/ui/jetbrains_logo.jpg")
    val kt = file("/contrib/qodana/core/src/org/jetbrains/qodana/staticAnalysis/inspections/runner/Logo.kt")
    return indexWith(listOf(logo,kt)) { index ->
      index.assertSearch("logo.jpg") {
        findsAllOf(logo)
        findsNoneOf(kt)
      }
    }
  }

  @TestFactory
  fun `ordering 3`() : List<DynamicNode> {
    val riderSemanticFileSearchEverywhereContributor = file("plugins/llm/searchEverywhere/embeddings/rider/src/RiderSemanticFileSearchEverywhereContributor.kt")
    val security_error = file("ruby/backend/rubystubs/rubystubs18/security_error.rb")
    return indexWith(listOf(riderSemanticFileSearchEverywhereContributor,security_error)) { index ->
      index.assertSearch("Sea") {
        findsAllOf(riderSemanticFileSearchEverywhereContributor)
        findsNoneOf(security_error)
      }

      index.assertSearch("SeaEver") {
        findsWithOrdering(listOf(riderSemanticFileSearchEverywhereContributor, security_error))

      }

      index.assertSearch("SeaEverContr") {
        findsAllOf(riderSemanticFileSearchEverywhereContributor)
        findsNoneOf(security_error)
      }

    }
  }
}