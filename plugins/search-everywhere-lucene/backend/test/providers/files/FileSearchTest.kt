// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.mock.MockVirtualFile
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import com.intellij.platform.searchEverywhere.SeFilterState
import com.intellij.platform.searchEverywhere.SeParams
import com.intellij.searchEverywhereLucene.backend.LuceneIndexTestBase
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileSearchAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME_ABBREVIATION
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILENAME_PART
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_FILETYPE
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_PATH
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.TOKEN_TYPE_PATH_SEGMENT
import com.intellij.searchEverywhereLucene.backend.util.TokenTypeFilteringAnalyzer
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.runBlocking
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

  private fun buildQueryOnlyUsingTokenTypes(input: String, vararg tokenTypes: String): Query {
    val analyzer = TokenTypeFilteringAnalyzer(FileSearchAnalyzer(), tokenTypes.toList())
    return FileIndex.buildQuery(SeParams(input, SeFilterState.Empty), analyzer)
  }

  override val isEquivalent: (Document, Document) -> Boolean = { d1, d2 ->
    d1.get(FileIndex.FILE_URL) == d2.get(FileIndex.FILE_URL)
  }

  private fun buildMockVirtualFile(path: String): MockVirtualFile {
    val segments = path.split('/')
    val file = MockVirtualFile(segments.last())
    var current: MockVirtualFile = file
    for (segment in segments.dropLast(1).reversed()) {
      val parent = MockVirtualFile(true, segment)
      parent.addChild(current)
      current = parent
    }
    return file
  }

  private fun file(path: String): Document {
    return runBlocking {
      readAction {
        val fileIndex = FileIndex.getInstance(project)
        fileIndex.getDocument(buildMockVirtualFile(path)).second
      }
    }
  }
  
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
    val baz = file("baz/Readme.md")


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

      index.assertSearch("md bar") {
        findsAllOf(bar)
        findsNoneOf(foo,baz)
      }

      index.assertSearch("bar baz") {
        findsNothing()
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

  @TestFactory
  fun `test TOKEN_TYPE_FILENAME can find results`(): List<DynamicNode> {
    val pet = file("Pet.java")
    val petController = file("PetController.java")
    val readme = file("foo/Readme.md")

    return indexWith(listOf(pet, petController, readme)) { index ->
      // Search using only FILENAME tokens
      index.assertSearch(
        input = "Pet.java",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILENAME) }
      ) {
        findsAllOf(pet)
      }

      index.assertSearch(
        input = "PetController.java",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILENAME) }
      ) {
        findsAllOf(petController)
      }

      index.assertSearch(
        input = "Readme.md",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILENAME) }
      ) {
        findsAllOf(readme)
      }
    }
  }

  @TestFactory
  fun `test TOKEN_TYPE_FILENAME_PART can find results`(): List<DynamicNode> {
    val petController = file("PetController.java")
    val searchEverywhereUI = file("SearchEverywhereUI.java")

    return indexWith(listOf(petController, searchEverywhereUI)) { index ->
      // Search using only FILENAME_PART tokens (camelCase parts)
      index.assertSearch(
        input = "Controller",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILENAME_PART) }
      ) {
        findsAllOf(petController)
      }


      index.assertSearch(
        input = "Pet",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILENAME_PART) }
      ) {
        findsAllOf(petController)
      }

      index.assertSearch(
        input = "Everywhere",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILENAME_PART) }
      ) {
        findsAllOf(searchEverywhereUI)
      }
    }
  }

  @TestFactory
  fun `test TOKEN_TYPE_FILENAME_ABBREVIATION can find results`(): List<DynamicNode> {
    val searchEverywhereUI = file("SearchEverywhereUI.java")
    val petController = file("PetController.java")

    return indexWith(listOf(searchEverywhereUI, petController)) { index ->
      // Search using only FILENAME_ABBREVIATION tokens (initials)
      index.assertSearch(
        input = "SearchEverywhereUI.java",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILENAME_ABBREVIATION) }
      ) {
        findsAllOf(searchEverywhereUI)
      }

      index.assertSearch(
        input = "PetController",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILENAME_ABBREVIATION) }
      ) {
        findsAllOf(petController)
      }
    }
  }

  @TestFactory
  fun `test TOKEN_TYPE_PATH can find results`(): List<DynamicNode> {
    val fooReadme = file("foo/bar/Readme.md")
    val bazReadme = file("baz/qux/Readme.md")

    return indexWith(listOf(fooReadme, bazReadme)) { index ->
      // Search using only PATH tokens (full path)
      index.assertSearch(
        input = "foo/bar/Readme.md",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_PATH) }
      ) {
        findsAllOf(fooReadme)
      }

      index.assertSearch(
        input = "baz/qux/Readme.md",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_PATH) }
      ) {
        findsAllOf(bazReadme)
      }
    }
  }

  @TestFactory
  fun `test TOKEN_TYPE_PATH_SEGMENT can find results`(): List<DynamicNode> {
    val fooReadme = file("foo/bar/Readme.md")
    val bazReadme = file("baz/bar/Readme.md")

    return indexWith(listOf(fooReadme, bazReadme)) { index ->
      // Search using only PATH_SEGMENT tokens (individual path components)
      index.assertSearch(
        input = "foo",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_PATH_SEGMENT) }
      ) {
        findsAllOf(fooReadme)
        findsNoneOf(bazReadme)
      }

      index.assertSearch(
        input = "bar",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_PATH_SEGMENT) }
      ) {
        findsAllOf(fooReadme, bazReadme)
      }

      index.assertSearch(
        input = "baz",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_PATH_SEGMENT) }
      ) {
        findsAllOf(bazReadme)
        findsNoneOf(fooReadme)
      }
    }
  }

  @TestFactory
  fun `test TOKEN_TYPE_FILETYPE can find results`(): List<DynamicNode> {
    val javaFile = file("Pet.java")
    val kotlinFile = file("Controller.kt")
    val markdownFile = file("Readme.md")

    return indexWith(listOf(javaFile, kotlinFile, markdownFile)) { index ->
      // Search using only FILETYPE tokens
      index.assertSearch(
        input = "java",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILETYPE) }
      ) {
        findsAllOf(javaFile)
        findsNoneOf(kotlinFile, markdownFile)
      }

      index.assertSearch(
        input = "kt",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILETYPE) }
      ) {
        findsAllOf(kotlinFile)
        findsNoneOf(javaFile, markdownFile)
      }

      index.assertSearch(
        input = "md",
        buildQuery = { buildQueryOnlyUsingTokenTypes(it, TOKEN_TYPE_FILETYPE) }
      ) {
        findsAllOf(markdownFile)
        findsNoneOf(javaFile, kotlinFile)
      }
    }
  }
}
