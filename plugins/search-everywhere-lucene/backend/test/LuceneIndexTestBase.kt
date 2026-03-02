// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.searchEverywhereLucene.backend

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.TestLoggerFactory
import com.intellij.testFramework.junit5.SystemProperty
import com.intellij.testFramework.rules.ProjectModelExtension
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.TextField
import org.apache.lucene.search.Query
import org.apache.lucene.search.ScoreDoc
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID

@SystemProperty("idea.test.logs.echo.debug.to.stdout", "true")
abstract class LuceneIndexTestBase {
  @RegisterExtension
  protected val projectModel: ProjectModelExtension = ProjectModelExtension()

  protected val project: Project get() = projectModel.project

  abstract val log: Logger

  @BeforeEach
  fun setupLogging() {
    TestLoggerFactory.enableDebugLogging(projectModel.disposableRule.disposable, "#com.intellij.searchEverywhereLucene.backend", "#${this::class.java.name}")
    val rootLogger = java.util.logging.Logger.getLogger("")
    if (rootLogger.handlers.none { it is TestLoggerFactory.LogToStdoutJulHandler }) {
      val handler = TestLoggerFactory.LogToStdoutJulHandler()
      rootLogger.addHandler(handler)
      com.intellij.openapi.util.Disposer.register(projectModel.disposableRule.disposable) {
        rootLogger.removeHandler(handler)
      }
    }
  }


  fun doc(vararg fields: Pair<String, String>): Document {
    val document = Document()
    for ((name, value) in fields) {
      document.add(TextField(name, value, Field.Store.YES))
    }
    return document
  }

  inner class SearchFlowAssert(val index: LuceneIndex, val query: Query, val results: List<Pair<ScoreDoc, Document>>, val isEquivalent: (Document, Document) -> Boolean) {
    private fun log(message: String) {
      this@LuceneIndexTestBase.log.info("[DEBUG_LOG] $message")
    }

    fun findsNothing() {
      log("finds Nothing")
      if (results.isNotEmpty()) {
        fail<Nothing>("Expected no results, but found ${results.size} documents:\n${results.joinToString("\n") { "  - ${it.second.asMap()}" }}")
      }
    }

    fun findsSomething() {
      log("finds Something")
      if (results.isEmpty()) {
        fail<Nothing>("Expected some results, but none were found for query: $query")
      }
    }

    fun findsAllOf(vararg expectedDocs: Document) {
      val expectedDocsString: String = expectedDocs.joinToString("", limit = 3, truncated = "... (total ${expectedDocs.size})", transform = { it.asMap().toString() })
      log("finds all of $expectedDocsString")
      for (expectedDoc in expectedDocs) {
        if (results.none { isEquivalent(it.second, expectedDoc) }) {
          fail<Nothing>("""
            Expected document not found in results.
            Missing Document: ${expectedDoc.asMap()}
            Total Results (${results.size}):
            ${results.joinToString("\n") { "  - ${it.second.asMap()}" }}
          """.trimIndent())
        }
      }
    }

    fun findsNoneOf(vararg expectedDocs: Document) {
      val expectedDocsString: String = expectedDocs.joinToString("", limit = 3, truncated = "... (total ${expectedDocs.size})", transform = { it.asMap().toString() })
      log("finds none of $expectedDocsString")
      for (expectedDoc in expectedDocs) {
        val found = results.find { isEquivalent(it.second, expectedDoc) }
        if (found != null) {
          fail<Nothing>("""
            Found document that should NOT be in results.
            Unexpected Document: ${expectedDoc.asMap()}
            Query: $query
          """.trimIndent())
        }
      }
    }

    fun findsWithOrdering(expectedOrder: List<Document>, containsAll: Boolean = true) {
      val all = if (containsAll) " all" else ""
      val expectedDocsString: String = expectedOrder.joinToString("", limit = 3, truncated = "... (total ${expectedOrder.size})", transform = { it.asMap().toString() })
      log("finds$all in order: $expectedDocsString")

      val foundDocs = expectedOrder.map { expected ->
        results.find { isEquivalent(it.second, expected) }
      }

      if (containsAll) {
        expectedOrder.forEachIndexed { index, doc ->
          if (foundDocs[index] == null) {
            fail<Nothing>("""
              Document not found in results (required by containsAll=true):
              Missing Document: ${doc.asMap()}
              Total Results (${results.size}):
              ${results.joinToString("\n") { "  - ${it.second.asMap()}" }}
            """.trimIndent())
          }
        }
      }

      val presentFoundDocs = foundDocs.filterNotNull()
      val actualIndices = presentFoundDocs.map { found -> results.indexOf(found) }

      for (i in 0 until actualIndices.size - 1) {
        if (actualIndices[i] > actualIndices[i + 1]) {
          val doc1 = presentFoundDocs[i]
          val doc2 = presentFoundDocs[i + 1]
          val scoreDoc1 = doc1.first
          val scoreDoc2 = doc2.first

          val explanation1 = index.withSearcher { it.explain(query, scoreDoc1.doc) }
          val explanation2 = index.withSearcher { it.explain(query, scoreDoc2.doc) }

          val diff = scoreDoc2.score - scoreDoc1.score
          fail<Nothing>("""
            Wrong relative ordering:

            Expected:  ${doc1.second.asMap()} > ${doc2.second.asMap()} 
            Actual:    ${doc2.second.asMap()} > ${doc1.second.asMap()} 

            Doc 1: ${doc1.second.asMap()}
            Score: ${scoreDoc1.score} ($diff LOWER than Doc 2)
            Explanation:
            ${explanation1.toString().prependIndent("  ")}

            Doc 2: ${doc2.second.asMap()}
            Score: ${scoreDoc2.score} ($diff HIGHER than Doc 1)
            Explanation:
            ${explanation2.toString().prependIndent("  ")}
          """.trimIndent())
        }
      }
    }

    private fun Document.asMap(): Map<String, String> {
      return fields.associate { it.name() to it.stringValue() }
    }
  }

  fun Document.asMap(): Map<String, String> {
    return fields.associate { it.name() to it.stringValue() }
  }
  
  open fun buildSimpleQuery(pattern: String): Query {
    throw UnsupportedOperationException("Override buildSimpleQuery or pass a buildQuery lambda to assertSearch")
  }

  open val isEquivalent: (Document, Document) -> Boolean = { d1, d2 -> d1.asMap() == d2.asMap() }

  private val dynamicNodes = mutableListOf<DynamicNode>()

  fun <T> LuceneIndex.assertSearch(
    input: T,
    buildQuery: (T) -> Query = { input -> buildSimpleQuery(input.toString()) },
    isEquivalent: (Document, Document) -> Boolean = this@LuceneIndexTestBase.isEquivalent,
    block: SearchFlowAssert.() -> Unit
  ) {
    val query = buildQuery(input)
    dynamicNodes.add(dynamicTest("Searching for `$input`") {
      runBlocking {
        val results = search(query).toList()
        SearchFlowAssert(this@assertSearch, query, results, isEquivalent).block()
      }
    })
  }

  fun indexWith(docs: List<Document>, block: suspend (LuceneIndex) -> Unit): List<DynamicNode> = runBlocking {
    val indexName = "test-index-${UUID.randomUUID()}"
    
    val luceneIndex = LuceneIndex(project, indexName, log)
    Disposer.register(projectModel.disposableRule.disposable, luceneIndex)

    luceneIndex.processChanges { writer ->
      writer.deleteAll()
      for (document in docs) {
        writer.addDocument(document)
      }
    }

    dynamicNodes.clear()
    block(luceneIndex)
    dynamicNodes.toList()
  }
}
