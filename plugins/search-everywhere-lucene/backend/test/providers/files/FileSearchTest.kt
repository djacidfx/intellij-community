// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.searchEverywhere.SeFilterState
import com.intellij.platform.searchEverywhere.SeParams
import com.intellij.searchEverywhereLucene.backend.LuceneIndexTestBase
import com.intellij.testFramework.junit5.TestApplication
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.document.Document
import org.apache.lucene.search.Query
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.TestFactory

@TestApplication
class FileSearchTest : LuceneIndexTestBase() {

  override val log: Logger = logger<FileSearchTest>()
  override val analyzer: Analyzer = FileIndex.getIndexingAnalyzer()

  override fun buildSimpleQuery(pattern: String): Query {
    return FileIndex.buildQuery(SeParams(pattern, SeFilterState.Empty))
  }

  override val isEquivalent: (Document, Document) -> Boolean = { d1, d2 ->
    d1.get(FileIndex.FILE_URL) == d2.get(FileIndex.FILE_URL)
  }
  
  fun file(fileName: String) = FileIndex.getDocument(MockVirtualFile(fileName)).second
  fun prefixesOf(fileName: String) = (1..fileName.length)
    .map { fileName.substring(0, it) }
  
  @TestFactory
  fun `test prefixes`(): List<DynamicNode> {
    return listOf(
      "Readme.md",
      "shell.nix",
      "temp.out.bc.exe",
      ".gitignore"
    )
      .flatMap { fileName ->
      val file = file(fileName)
      indexWith(listOf(file)) { index ->
        prefixesOf(fileName)
          .forEach { prefix ->
            index.assertSearch(prefix) {
              findsAllOf(file)
            }
          }
      }
    }
  }

  @TestFactory
  fun `ensure each part must match`(): List<DynamicNode> {

    val foo = file("foo/Readme.md")
    val bar = file("bar/Readme.md")


    return indexWith(listOf(foo, bar)) { index ->
      index.assertSearch("Readme.md") {
        findsAllOf(foo, bar)
      }

      index.assertSearch("Readme.md foo") {
        findsAllOf(foo)
        findsNoneOf(bar)
      }

      index.assertSearch("Readme.md bar") {
        findsAllOf(bar)
        findsNoneOf(foo)
      }
    }
  }
  
  @TestFactory
  fun `test pet search`(): List<DynamicNode> {
    val pet = file("Pet.java")
    val petC = file("PetController.java")

    return indexWith(listOf(pet,petC)) { index ->
      index.assertSearch("Pet.java") {
        findsWithOrdering(listOf(pet,petC))
      }
    }
  }

  @TestFactory
  fun `test case insensitive search`(): List<DynamicNode> {
    val pet = file("Pet.java")
    val petC = file("PetController.java")

    return indexWith(listOf(pet, petC)) { index ->
      index.assertSearch("pet.java") {
        findsWithOrdering(listOf(pet, petC))
      }
    }
  }


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
