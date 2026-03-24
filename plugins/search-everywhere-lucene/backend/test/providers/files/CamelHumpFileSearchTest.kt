package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.TestFactory

@TestApplication
class CamelHumpFileSearchTest : FileSearchTestBase() {
  @TestFactory
  fun `camel search`(): List<DynamicNode> {
    val seaEverContr = file("SearchEverywhereContributor.kt")
    return indexWith(listOf(seaEverContr)) { index ->
      index.assertSearch("seaEverContr") {
        findsAllOf(seaEverContr)
      }

      index.assertSearch("SEC") {
        findsAllOf(seaEverContr)
      }

      index.assertSearch("SECT") {
        findsNothing()
      }
    }
  }

}