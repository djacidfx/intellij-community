package com.intellij.searchEverywhereLucene.backend.providers.files

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.searchEverywhere.SeParams
import com.intellij.searchEverywhereLucene.backend.LuceneIndex
import com.intellij.searchEverywhereLucene.backend.SearchEverywhereLucenePluginDisposable
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileNameAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FilePathAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileSearchAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileTokenType
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.FileTypeAnalyzer
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.MultiTypeAttribute
import com.intellij.searchEverywhereLucene.backend.providers.files.analysis.WordAttribute
import com.intellij.searchEverywhereLucene.common.SearchEverywhereLuceneProviderIdUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.FieldType
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexOptions
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.PROJECT)
class FileIndex(val project: Project, coroutineScope: CoroutineScope) : Disposable {
  private val indexingEnabled = isIndexingEnabled()
  private val luceneIndex by lazy(LazyThreadSafetyMode.NONE) {
    LuceneIndex(project, SearchEverywhereLuceneProviderIdUtils.LUCENE_FILES, LOG, ANALYZER)
  }
  private val scheduledIndexingOps = Channel<LuceneFileIndexOperation>(capacity = Channel.UNLIMITED)
  val initialIndexingCompleted: CompletableDeferred<Unit> = CompletableDeferred()

  init {
    Disposer.register(SearchEverywhereLucenePluginDisposable.getInstance(project), this)
    if (!indexingEnabled) {
      LOG.info("Lucene file indexing is disabled by registry key $LUCENE_INDEX_ENABLED_REGISTRY_KEY.")
    }
    else {
      Disposer.register(this, luceneIndex)

      coroutineScope.launch {
        // Wait until the config is loaded, and we can expect `ProjectFileIndex.getInstance()` to return the files to index.

        LOG.debug { "File Index in ${project.name} project stated processing changes..." }

        scheduledIndexingOps.consumeAsFlow().debounceBatch(1.seconds).collect { ops ->
          if (ops.size == 1) {
            processFileIndexOp(ops.first())
            return@collect
          }

          if (ops.any { it is LuceneFileIndexOperation.IndexAll }) {
            // If ANY one of the ops is a reindexing request, we can also drop all other updates, as reindexing will pick up the updated state anyway.
            processFileIndexOp(LuceneFileIndexOperation.IndexAll)
          }
          else {
            // Since all others are ReindexFiles, we can merge them to reduce the number of times indexing runs:
            val mergedFiles = ops.asSequence()
              .filterIsInstance<LuceneFileIndexOperation.ReindexFiles>()
              .flatMap { it.changedFiles }
              .toSet()

            val mergedUrls = ops.asSequence()
              .filterIsInstance<LuceneFileIndexOperation.ReindexFiles>()
              .flatMap { it.changedUrls }
              .toSet()

            processFileIndexOp(LuceneFileIndexOperation.ReindexFiles(mergedFiles, mergedUrls))
          }
        }
      }
    }
  }

  @TestOnly
  suspend fun awaitIndexCreation() {
    processFileIndexOp(LuceneFileIndexOperation.IndexAll)
  }

  // TODO somehow inform UI that indexing is in progress
  private suspend fun processFileIndexOp(op: LuceneFileIndexOperation) {
    when (op) {
      LuceneFileIndexOperation.IndexAll -> {
        LOG.debug("Indexing all files")

        val fileIndex = ProjectFileIndex.getInstance(project)

        //TODO there can be duplicate files in here, but I dont think its a problem?

        val docs = mutableListOf<Document>()
        readAction {
          fileIndex.iterateContent { file ->
            // also index Dirs as the current File Search can also find directories.
            if (file.isValid) {
              val (_, doc) = getDocument(file)
              docs.add(doc)
            }
            else {
              LOG.info("Skipping indexing ${file.path}, because it is not valid. But we assume fileIndex.iterateContent only returns valid files.")
            }


            true // continue iteration
          }
        }

        luceneIndex.processChanges { writer ->
          writer.deleteAll()
          writer.addDocuments(docs)
          LOG.debug { "Registered all ${docs.size} files for the next lucene index commit." }
        }
        initialIndexingCompleted.complete(Unit)
      }

      is LuceneFileIndexOperation.ReindexFiles -> {
        val fileIndex = ProjectFileIndex.getInstance(project)
        val filesToReindex = mutableListOf<VirtualFile>()
        val urlsToDelete = mutableListOf<Term>()
        
        
        // The reindexing Op may point to directories that should be reindexed, so we must reindex the contents of the dir, as these paths have changed.
        readAction {
          val virtualFiles = mutableListOf<VirtualFile>()
          virtualFiles.addAll(op.changedFiles)

          op.changedUrls.forEach { url ->
            val virtualFile = VirtualFileManager.getInstance().findFileByUrl(url) ?: let {
              urlsToDelete.add(getTerm(url))
              return@forEach
            } 
            virtualFiles.add(virtualFile)
          }

          virtualFiles.forEach { virtualFile ->
            if (!fileIndex.isInProject(virtualFile)) return@forEach

            if (!virtualFile.isValid) {
              LOG.info("Skipping indexing ${virtualFile.url}, because it is not valid. Scheduling for deletion instead. We assume the files scheduled for reindex are valid files.")
              urlsToDelete.add(getTerm(virtualFile.url))
              return@forEach
            }
            if (!virtualFile.isDirectory) {
              filesToReindex.add(virtualFile)
            } else {
              // Should be used from readAction
              fileIndex.iterateContentUnderDirectory(virtualFile) { file ->
                if (!file.isDirectory) {
                  filesToReindex.add(file)
                }
                true // continue iteration
              }
            }
          }
        }

        if (filesToReindex.isEmpty() && urlsToDelete.isEmpty()) return
        LOG.debug {"Reindexing ${filesToReindex.size} files, deleting ${urlsToDelete.size} files" }

        val termsAndDocs = readAction { filesToReindex.map { getDocument(it) } }

        luceneIndex.processChanges { writer ->
          termsAndDocs.forEach { (term, doc) ->
            writer.updateDocument(term, doc)
          }

          urlsToDelete.forEach { writer.deleteDocuments(it) }

          LOG.debug("Reindexed all updated files for the next lucene index commit.")
        }
      }
    }
  }

  suspend fun awaitInitialIndexing(): Unit = initialIndexingCompleted.await()

  fun scheduleIndexingOp(op: LuceneFileIndexOperation) {
    if (!indexingEnabled) return

    // Since the channel is unbounded, the sending must succeed.
    val r = scheduledIndexingOps.trySend(op)
    if (r.isFailure) {
      throw IllegalStateException("The channel failed to send, even though its unbounded!")
    }

  }


  fun search(params: SeParams): Flow<LuceneFileSearchResult> {
    if (!indexingEnabled) return emptyFlow()

    val query = buildQuery(params)
    val deletedFilesToRemoveFromIndex = mutableSetOf<String>()
    return luceneIndex.search(query).mapNotNull { (scoreDoc, doc) ->
      //LOG.debug { "Search \"${params.inputQuery}\" returned $doc with score ${scoreDoc.score}" }
      val url = doc.get(FILE_URL)
      val virtualFile = VirtualFileManager.getInstance().findFileByUrl(url) ?: let {
        deletedFilesToRemoveFromIndex.add(url)
        return@mapNotNull null
      }
      LuceneFileSearchResult(virtualFile, scoreDoc.score)
    }.onCompletion {
      //This will fire oftentimes, as each character typed by the user causes a new search.
      //And since the same deleted files are likely showing up repeatedly, there are a bunch of requests to delete the same file.
      //We could track the deleted files in the FilesProvider instead, but this would make the FileIndex interface more complex.
      //The debouncing/merging logic in place should be enough to handle this anyway.
      if (deletedFilesToRemoveFromIndex.isNotEmpty()) {
        LOG.debug { "Scheduling deletion of ${deletedFilesToRemoveFromIndex.size} files from index: ${deletedFilesToRemoveFromIndex.joinToString(", ", limit = 10)}" }
        scheduleIndexingOp(LuceneFileIndexOperation.ReindexFiles(changedUrls = deletedFilesToRemoveFromIndex))
      }
    }
  }

  override fun dispose() {}

  @Throws(IOException::class)
  fun getDocument(virtualFile: VirtualFile): Pair<Term, Document> {
    val document = Document()

    val tokenizedField = FieldType()
    tokenizedField.setStored(true)
    tokenizedField.setTokenized(true)
    tokenizedField.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS)

    // Get the path relative to the project content root
    val relativePath = getRelativePathForFile(virtualFile)

    val fields = mutableListOf(
      StringField(FILE_URL, virtualFile.url, Field.Store.YES),
      TextField(FILE_NAME, virtualFile.name, Field.Store.NO),
      TextField(FILE_RELATIVE_PATH, relativePath, Field.Store.NO),
    )

    if (virtualFile.extension != null) {
      fields.add(TextField(FILE_TYPE, virtualFile.extension, Field.Store.NO))

    }


    virtualFile.fileType

    fields.forEach { document.add(it) }

    val term = getTerm(virtualFile.url)

    return Pair(term, document)
  }


  private fun getRelativePathForFile(virtualFile: VirtualFile): String {
    // Try to get path relative to content root; fall back to filename if not in a content root
    val contentRoot = ProjectFileIndex.getInstance(project).getContentRootForFile(virtualFile) // REQUIRES READ-ACTION
    // TODO Find out if there are other ways to mock the files so that this special handling is not necessary?

    if (contentRoot != null) {
      return VfsUtil.getRelativePath(virtualFile, contentRoot) ?: virtualFile.name
    }

    // No content root: walk parent chain to build relative path (supports mock file trees in tests)
    val parts = mutableListOf<String>()
    var current: VirtualFile? = virtualFile
    while (current != null) {
      parts.add(0, current.name)
      current = current.parent
    }
    return parts.joinToString("/")
  }

  companion object {
    fun getInstance(project: Project): FileIndex = project.service()
    fun getInstanceIfEnabled(project: Project): FileIndex? = if (isIndexingEnabled()) project.service() else null
    fun isIndexingEnabled(): Boolean = Registry.`is`(LUCENE_INDEX_ENABLED_REGISTRY_KEY, true)

    val LOG: Logger = logger<FileIndex>()
    const val LUCENE_INDEX_ENABLED_REGISTRY_KEY: String = "search.everywhere.lucene.index.enabled"
    const val FILE_NAME: String = "fileName"
    const val FILE_RELATIVE_PATH: String = "fileRelativePath"
    const val FILE_URL: String = "uri"
    const val FILE_TYPE: String = "type"


    fun getIndexingAnalyzer() = PerFieldAnalyzerWrapper(FileNameAnalyzer(), mapOf(
      FILE_RELATIVE_PATH to FilePathAnalyzer(),
      FILE_TYPE to FileTypeAnalyzer()
    ))

    fun buildQuery(params: SeParams, analyzer: Analyzer = FileSearchAnalyzer()): Query {
      val tokenStream = analyzer.tokenStream("", params.inputQuery)
      val termAttr = tokenStream.addAttribute(CharTermAttribute::class.java)
      val wordAttr = tokenStream.addAttribute(WordAttribute::class.java)
      val multiTypeAttr = tokenStream.addAttribute(MultiTypeAttribute::class.java)

      val wordQueries = mutableMapOf<Int, MutableList<Query>>()

      tokenStream.reset()
      while (tokenStream.incrementToken()) {
        val termString = termAttr.toString()
        val wordIndex = wordAttr.wordIndex

        val typesToProcess = multiTypeAttr.activeTypes()
        for (tokenType in typesToProcess) {
          val query = when (tokenType) {
            FileTokenType.PATH, FileTokenType.PATH_SEGMENT, FileTokenType.PATH_SEGMENT_PREFIX ->
              PrefixQuery(Term(FILE_RELATIVE_PATH, termString))
            FileTokenType.FILENAME, FileTokenType.FILENAME_PART, FileTokenType.FILENAME_ABBREVIATION ->
              PrefixQuery(Term(FILE_NAME, termString))
            FileTokenType.FILETYPE ->
              TermQuery(Term(FILE_TYPE, termString))
          }
          wordQueries.computeIfAbsent(wordIndex) { mutableListOf() }.add(query)
        }
      }
      tokenStream.end()
      tokenStream.close()

      if (wordQueries.isEmpty()) return BooleanQuery.Builder().build()

      val mainBq = BooleanQuery.Builder()
      val sortedWordIndices = wordQueries.keys.sorted()
      for (index in sortedWordIndices) {
        val queries = wordQueries[index]!!
        if (queries.isEmpty()) continue
        if (queries.size == 1) {
          mainBq.add(queries[0], BooleanClause.Occur.MUST)
        }
        else {
          mainBq.add(DisjunctionMaxQuery(queries, 0.1f), BooleanClause.Occur.MUST)
        }
      }

      val query = mainBq.build()
      LOG.debug { "Built query for \"${params.inputQuery}\": $query" }
      return query
    }

    private fun getTerm(url: String): Term {
      val term = Term(FILE_URL, url)
      return term
    }
  }
}

sealed class LuceneFileIndexOperation {
  data object IndexAll : LuceneFileIndexOperation()
  data class ReindexFiles(val changedFiles: Set<VirtualFile> = emptySet(), val changedUrls: Set<String> = emptySet()) : LuceneFileIndexOperation()
}


@OptIn(ExperimentalCoroutinesApi::class)
fun <T> Flow<T>.debounceBatch(
  timeout: Duration,
  maxSize: Int = Int.MAX_VALUE,
): Flow<List<T>> = channelFlow {
  val ch: ReceiveChannel<T> = this@debounceBatch.produceIn(this)

  val batch = ArrayList<T>()

  suspend fun flush() {
    if (batch.isNotEmpty()) {
      send(batch.toList())
      batch.clear()
    }
  }

  while (true) {
    val got = select {
      ch.onReceiveCatching { result ->
        val v = result.getOrNull()
        if (v == null) {
          // upstream completed
          false
        }
        else {
          batch.add(v)
          if (batch.size >= maxSize) flush()
          true
        }
      }

      onTimeout(timeout) {
        flush()
        true
      }
    }

    if (!got) break
  }

  flush()
}
