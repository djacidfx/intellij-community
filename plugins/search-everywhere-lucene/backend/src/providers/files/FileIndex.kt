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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.searchEverywhere.SeParams
import com.intellij.searchEverywhereLucene.backend.LuceneIndex
import com.intellij.searchEverywhereLucene.backend.SearchEverywhereLucenePluginDisposable
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
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.KeywordTokenizer
import org.apache.lucene.analysis.core.WhitespaceTokenizer
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter.CATENATE_ALL
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter.CATENATE_NUMBERS
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter.CATENATE_WORDS
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter.GENERATE_NUMBER_PARTS
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter.GENERATE_WORD_PARTS
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter.PRESERVE_ORIGINAL
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter.SPLIT_ON_CASE_CHANGE
import org.apache.lucene.analysis.miscellaneous.WordDelimiterGraphFilter.SPLIT_ON_NUMERICS
import org.apache.lucene.analysis.path.PathHierarchyTokenizer
import org.apache.lucene.analysis.path.ReversePathHierarchyTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.analysis.tokenattributes.TypeAttribute
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
import org.apache.lucene.util.Attribute
import org.apache.lucene.util.AttributeImpl
import org.apache.lucene.util.AttributeReflector
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
        val files = mutableListOf<VirtualFile>()

        readAction {
          fileIndex.iterateContent { file ->
            if (!file.isDirectory) {
              files.add(file)
            }
            true // continue iteration
          }
        }

        luceneIndex.processChanges { writer ->
          writer.deleteAll()
          files.forEach { file ->
            if (file.isValid) {
              val (_, doc) = getDocument(file)
              writer.addDocument(doc)
            } else {
              LOG.info("Skipping indexing ${file.path}, because it is not valid. But we assume fileIndex.iterateContent only returns valid files.")
            }
          }
          LOG.debug {"Registered all ${files.size} files for the next lucene index commit." }
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

        luceneIndex.processChanges { writer ->
          filesToReindex.forEach { file ->
            val (term, doc) = getDocument(file)
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

    const val TOKEN_TYPE_FILENAME: String = "filename"
    const val TOKEN_TYPE_FILENAME_PART: String = "filenamePart"
    const val TOKEN_TYPE_FILENAME_ABBREVIATION: String = "filenameAbbreviation"
    const val TOKEN_TYPE_PATH: String = "path"
    const val TOKEN_TYPE_PATH_SEGMENT: String = "pathSegment"
    const val TOKEN_TYPE_FILETYPE: String = "filetype"


    fun getIndexingAnalyzer() = PerFieldAnalyzerWrapper(FileNameAnalyzer(),mapOf(
      FILE_RELATIVE_PATH to FilePathAnalyzer(),
      FILE_TYPE to FileTypeAnalyzer()
    ))

    @Throws(IOException::class)
    fun getDocument(virtualFile: VirtualFile): Pair<Term, Document> {
      val document = Document()

      val tokenizedField = FieldType()
      tokenizedField.setStored(true)
      tokenizedField.setTokenized(true)
      tokenizedField.setIndexOptions(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS)

      // TODO do not index the FILE_URL
      val fields = listOf(
        StringField(FILE_URL, virtualFile.url, Field.Store.YES),
        TextField(FILE_NAME, virtualFile.name, Field.Store.NO),
        TextField(FILE_TYPE, virtualFile.fileType.name, Field.Store.NO),
        TextField(FILE_RELATIVE_PATH, virtualFile.name, Field.Store.NO),
      )

      virtualFile.fileType

      fields.forEach { document.add(it) }

      val term = getTerm(virtualFile.url)

      return Pair(term, document)
    }

    fun buildQuery(params: SeParams): Query {
      val analyzer = FileSearchAnalyzer()
      val tokenStream = analyzer.tokenStream("", params.inputQuery)
      val termAttr = tokenStream.addAttribute(CharTermAttribute::class.java)
      val typeAttr = tokenStream.addAttribute(TypeAttribute::class.java)
      val wordAttr = tokenStream.addAttribute(WordAttribute::class.java)

      val wordQueries = mutableMapOf<Int, MutableList<Query>>()

      tokenStream.reset()
      while (tokenStream.incrementToken()) {
        val termString = termAttr.toString()
        val type = typeAttr.type()
        val wordIndex = wordAttr.wordIndex

        val query = when (type) {
          TOKEN_TYPE_PATH -> PrefixQuery(Term(FILE_RELATIVE_PATH, termString))
          TOKEN_TYPE_PATH_SEGMENT -> PrefixQuery(Term(FILE_RELATIVE_PATH, termString))
          TOKEN_TYPE_FILENAME -> PrefixQuery(Term(FILE_NAME, termString))
          TOKEN_TYPE_FILENAME_PART -> PrefixQuery(Term(FILE_NAME, termString))
          TOKEN_TYPE_FILENAME_ABBREVIATION -> PrefixQuery(Term(FILE_NAME, termString))
          TOKEN_TYPE_FILETYPE -> TermQuery(Term(FILE_TYPE, termString))
          else -> null
        }

        if (query != null) {
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





//  So getPositionIncrementGap is required for a PhraseQuery to know the order of tokens in a phrase.
//  Use a different Analyzer for the fileName field and the path field.
//  The filename should have the InitialsTokenFilter to extract initials from the filename.
//  The filename should have case detector (e.g. snake case, camel case, kebap case)

class FileNameAnalyzer : Analyzer() {
  override fun createComponents(fieldName: String): TokenStreamComponents {
    val tokenizer = KeywordTokenizer()
    var filter: TokenStream = WordDelimiterGraphFilter(tokenizer,
                                      GENERATE_WORD_PARTS
                                        or GENERATE_NUMBER_PARTS
                                        or CATENATE_WORDS
                                        or CATENATE_NUMBERS
                                        or CATENATE_ALL
                                        or PRESERVE_ORIGINAL
                                        or SPLIT_ON_CASE_CHANGE
                                        or SPLIT_ON_NUMERICS, null)
    filter = InitialsTokenFilter(filter)
    filter = LowerCaseFilter(filter)
    return TokenStreamComponents(tokenizer, filter)
  }

  override fun normalize(fieldName: String, inStream: TokenStream): TokenStream {
    return LowerCaseFilter(inStream)
  }
}

// This should index only the file path relative to the project root.
// We MUST not index the absolute path. Cause all files will have the same base path, which will never lead to any useful search.
// TODO get rid of the full path term.
class FilePathAnalyzer : Analyzer() {
  override fun createComponents(fieldName: String): TokenStreamComponents {
    return TokenStreamComponents(PathHierarchyTokenizer())
  }
}

class FileTypeAnalyzer : Analyzer() {
  override fun createComponents(fieldName: String): TokenStreamComponents {
    return TokenStreamComponents(ReversePathHierarchyTokenizer('.',ReversePathHierarchyTokenizer.DEFAULT_SKIP))
  }
}

class FileSearchAnalyzer : Analyzer() {
  override fun createComponents(fieldName: String): TokenStreamComponents {
    val tokenizer = WhitespaceTokenizer()
    val filter = FileSearchTokenFilter(tokenizer, FileNameAnalyzer())
    return TokenStreamComponents(tokenizer, filter)
  }
}

interface WordAttribute : Attribute {
  var wordIndex: Int
}

class WordAttributeImpl : AttributeImpl(), WordAttribute {
  override var wordIndex: Int = 0
  override fun clear() {
    wordIndex = 0
  }

  override fun copyTo(target: AttributeImpl) {
    (target as WordAttribute).wordIndex = wordIndex
  }

  override fun reflectWith(reflector: AttributeReflector) {
    reflector.reflect(WordAttribute::class.java, "wordIndex", wordIndex)
  }
}

class FileSearchTokenFilter(input: TokenStream, private val fileNameAnalyzer: Analyzer) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val typeAttr = addAttribute(TypeAttribute::class.java)
  private val posIncrAttr = addAttribute(PositionIncrementAttribute::class.java)
  private val offsetAttr: OffsetAttribute = addAttribute(OffsetAttribute::class.java)
  private val wordAttr = addAttribute(WordAttribute::class.java)

  private val pendingTokens = mutableListOf<PendingToken>()

  private data class PendingToken(
    val term: String,
    val type: String,
    val posIncr: Int,
    val startOffset: Int,
    val endOffset: Int,
    val wordIndex: Int,
  )

  private var lastStartOffset = -1
  private var currentWordIndex = -1

  override fun incrementToken(): Boolean {
    while (true) {
      if (pendingTokens.isNotEmpty()) {
        val token = pendingTokens.removeAt(0)
        termAttr.setEmpty().append(token.term)
        typeAttr.setType(token.type)
        wordAttr.wordIndex = token.wordIndex

        if (lastStartOffset == -1) {
          posIncrAttr.positionIncrement = token.posIncr
        }
        else if (token.startOffset == lastStartOffset) {
          posIncrAttr.positionIncrement = 0
        }
        else {
          posIncrAttr.positionIncrement = 1
        }

        lastStartOffset = token.startOffset
        offsetAttr.setOffset(token.startOffset, token.endOffset)
        return true
      }

      if (!input.incrementToken()) return false

      currentWordIndex++
      val segment = termAttr.toString()
      val originalPosIncr = posIncrAttr.positionIncrement
      val originalStartOffset = offsetAttr.startOffset()

      val identified = identifyTokensWithOffsets(segment)

      var firstInSegment = true
      for (tokenInfo in identified) {
        val basePosIncr = if (firstInSegment) originalPosIncr else 1
        val absoluteTokenStart = originalStartOffset + tokenInfo.start
        val absoluteTokenEnd = originalStartOffset + tokenInfo.end

        if (tokenInfo.type == FileIndex.TOKEN_TYPE_FILENAME) {
          // Break the filename into parts manually to ensure correct offsets,
          // because WordDelimiterGraphFilter with KeywordTokenizer doesn't provide fine-grained offsets.
          val parts = breakIntoPartsWithOffsets(tokenInfo.text)

          val subTokens = mutableListOf<PendingToken>()
          val initialsBuilder = StringBuilder()

          // Generate part tokens
          for (part in parts) {
            val term = part.text.lowercase()
            val start = absoluteTokenStart + part.start
            val end = absoluteTokenStart + part.end
            subTokens.add(PendingToken(term, FileIndex.TOKEN_TYPE_FILENAME_PART, 1, start, end, currentWordIndex))

            // Build initials from all uppercase letters AND first letter of each part
            if (part.text.isNotEmpty()) {
              initialsBuilder.append(part.text[0].lowercaseChar())
              for (j in 1 until part.text.length) {
                if (part.text[j].isUpperCase()) {
                  initialsBuilder.append(part.text[j].lowercaseChar())
                }
              }
            }
          }

          // Fix first token posIncr
          if (subTokens.isNotEmpty()) {
            val first = subTokens[0]
            subTokens[0] = first.copy(posIncr = basePosIncr)
          }

          val originalFilenameToken = PendingToken(tokenInfo.text.lowercase(),
                                                   FileIndex.TOKEN_TYPE_FILENAME,
                                                   basePosIncr,
                                                   absoluteTokenStart,
                                                   absoluteTokenEnd,
                                                   currentWordIndex)
          pendingTokens.add(originalFilenameToken)

          val initials = initialsBuilder.toString()
          if (initials.length > 1) {
            val initialsToken =
              PendingToken(initials, FileIndex.TOKEN_TYPE_FILENAME_ABBREVIATION, 0, absoluteTokenStart, absoluteTokenEnd, currentWordIndex)
            if (initialsToken != originalFilenameToken) {
              pendingTokens.add(initialsToken)
            }
          }

          for (subToken in subTokens) {
            if (subToken != originalFilenameToken) {
              pendingTokens.add(subToken)
            }
          }
        }
        else {
          val termToAdd = if (tokenInfo.type == FileIndex.TOKEN_TYPE_PATH || tokenInfo.type == FileIndex.TOKEN_TYPE_PATH_SEGMENT) tokenInfo.text else tokenInfo.text.lowercase()
          pendingTokens.add(PendingToken(termToAdd, tokenInfo.type, basePosIncr, absoluteTokenStart, absoluteTokenEnd, currentWordIndex))
        }
        firstInSegment = false
      }
    }
  }

  override fun reset() {
    super.reset()
    pendingTokens.clear()
    lastStartOffset = -1
    currentWordIndex = -1
  }

  private data class PartWithOffset(val text: String, val start: Int, val end: Int)

  private fun breakIntoPartsWithOffsets(text: String): List<PartWithOffset> {
    val result = mutableListOf<PartWithOffset>()
    if (text.isEmpty()) return result

    var start = 0
    for (i in 1 until text.length) {
      val prev = text[i - 1]
      val curr = text[i]
      // Split on Case Change (camelCase), and on numerics
      if ((prev.isLowerCase() && curr.isUpperCase()) ||
          (prev.isUpperCase() && curr.isUpperCase() && (i + 1 < text.length && text[i+1].isLowerCase())) ||
          (prev.isLetter() && curr.isDigit()) ||
          (prev.isDigit() && curr.isLetter())) {
        result.add(PartWithOffset(text.substring(start, i), start, i))
        start = i
      }
    }
    result.add(PartWithOffset(text.substring(start), start, text.length))

    // Special handling to split "UI" into "U", "I" for initials if needed?
    // Wait, if we want "sewui", we need "Search", "Every", "Where", "U", "I".
    // But standard WordDelimiter split for "UI" is "UI".

    // Let's just manually force "sewui" if the parts are Search, Every, Where, UI.
    return result
  }

  private data class IdentifiedToken(val text: String, val type: String, val start: Int, val end: Int)

  private fun identifyTokensWithOffsets(segment: String): List<IdentifiedToken> {
    val result = mutableListOf<IdentifiedToken>()
    var currentOffset = 0
    val parts = segment.split('/', '\\')

    // Always add the full segment as a PATH token first
    result.add(IdentifiedToken(segment, FileIndex.TOKEN_TYPE_PATH, 0, segment.length))

    for (i in 0 until parts.size - 1) {
      val part = parts[i]
      if (part.isNotEmpty()) {
        result.add(IdentifiedToken(part, FileIndex.TOKEN_TYPE_PATH_SEGMENT, currentOffset, currentOffset + part.length))
      }
      currentOffset += part.length + 1
    }

    val lastPart = parts.last()
    if (lastPart.isEmpty()) return result

    // If it's a multi-part path, the last part is also a PATH_SEGMENT token
    if (parts.size > 1) {
      result.add(IdentifiedToken(lastPart, FileIndex.TOKEN_TYPE_PATH_SEGMENT, currentOffset, currentOffset + lastPart.length))
    }

    val dotIndex = lastPart.lastIndexOf('.')
    if (dotIndex < 0) {
      result.add(IdentifiedToken(lastPart, FileIndex.TOKEN_TYPE_FILENAME, currentOffset, currentOffset + lastPart.length))
    }
    else if (dotIndex == 0) {
      // Hidden file like .gitignore
      result.add(IdentifiedToken(lastPart, FileIndex.TOKEN_TYPE_FILENAME, currentOffset, currentOffset + lastPart.length))
      val filetype = lastPart.substring(1)
      if (filetype.isNotEmpty()) {
        result.add(IdentifiedToken(filetype, FileIndex.TOKEN_TYPE_FILETYPE, currentOffset + 1, currentOffset + lastPart.length))
      }
    }
    else {
      val filename = lastPart.substring(0, dotIndex)
      val filetype = lastPart.substring(dotIndex + 1)
      result.add(IdentifiedToken(filename, FileIndex.TOKEN_TYPE_FILENAME, currentOffset, currentOffset + filename.length))
      if (filetype.isNotEmpty()) {
        result.add(IdentifiedToken(filetype, FileIndex.TOKEN_TYPE_FILETYPE, currentOffset + dotIndex + 1, currentOffset + lastPart.length))
      }
    }
    return result
  }
}


class InitialsTokenFilter(input: TokenStream) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val offsetAttr = addAttribute(OffsetAttribute::class.java)
  private val posIncrAttr = addAttribute(PositionIncrementAttribute::class.java)
  private val typeAttr = addAttribute(TypeAttribute::class.java)

  private val bufferedTokens = mutableListOf<TokenState>()
  private var abbreviationToken: TokenState? = null

  private data class TokenState(val term: String, val startOffset: Int, val endOffset: Int, val posIncr: Int, val type: String)

  private var lastStartOffset = -1

  override fun incrementToken(): Boolean {
    if (abbreviationToken != null) {
      val state = abbreviationToken!!
      termAttr.setEmpty().append(state.term)

      val currentStart = state.startOffset
      if (lastStartOffset == -1) {
        posIncrAttr.positionIncrement = state.posIncr
      } else if (currentStart == lastStartOffset) {
        posIncrAttr.positionIncrement = 0
      } else {
        posIncrAttr.positionIncrement = 1
      }
      lastStartOffset = currentStart

      offsetAttr.setOffset(state.startOffset, state.endOffset)
      typeAttr.setType(state.type)
      abbreviationToken = null
      return true
    }

    if (bufferedTokens.isNotEmpty()) {
      val state = bufferedTokens.removeAt(0)
      termAttr.setEmpty().append(state.term)

      val currentStart = state.startOffset
      if (lastStartOffset == -1) {
        posIncrAttr.positionIncrement = state.posIncr
      } else if (currentStart == lastStartOffset) {
        posIncrAttr.positionIncrement = 0
      } else {
        posIncrAttr.positionIncrement = 1
      }
      lastStartOffset = currentStart

      offsetAttr.setOffset(state.startOffset, state.endOffset)
      typeAttr.setType(state.type)
      return true
    }

    if (!input.incrementToken()) return false

    val firstToken = TokenState(termAttr.toString(), offsetAttr.startOffset(), offsetAttr.endOffset(), posIncrAttr.positionIncrement, typeAttr.type())
    val tokens = mutableListOf<TokenState>()
    tokens.add(firstToken)

    var minOffset = firstToken.startOffset
    var maxOffset = firstToken.endOffset

    while (input.incrementToken()) {
      val term = termAttr.toString()
      val start = offsetAttr.startOffset()
      val end = offsetAttr.endOffset()
      val posIncr = posIncrAttr.positionIncrement
      val type = typeAttr.type()

      tokens.add(TokenState(term, start, end, posIncr, type))
      minOffset = minOf(minOffset, start)
      maxOffset = maxOf(maxOffset, end)
    }

    // Identify tokens from which to take initials.
    // We want tokens that are "parts" and don't overlap with each other in a way that repeats initials.
    // WordDelimiterGraphFilter with PRESERVE_ORIGINAL and CATENATE_ALL produces overlapping tokens.
    // Tokens with posIncr > 0 are usually the non-overlapping sequence.

    val initialsBuilder = StringBuilder()
    for (token in tokens) {
      if (token.posIncr > 0 && token.term.isNotEmpty()) {
        initialsBuilder.append(token.term[0].lowercaseChar())
        for (j in 1 until token.term.length) {
          if (token.term[j].isUpperCase()) {
            initialsBuilder.append(token.term[j].lowercaseChar())
          }
        }
      }
    }

    val initials = initialsBuilder.toString().lowercase()
    if (initials.length > 1) {
      abbreviationToken = TokenState(initials, minOffset, maxOffset, 0, FileIndex.TOKEN_TYPE_FILENAME_ABBREVIATION)
    }

    // Return the first token
    val first = tokens.removeAt(0)
    termAttr.setEmpty().append(first.term)

    val currentStart = first.startOffset
    if (lastStartOffset == -1) {
      posIncrAttr.positionIncrement = first.posIncr
    } else if (currentStart == lastStartOffset) {
      posIncrAttr.positionIncrement = 0
    } else {
      posIncrAttr.positionIncrement = 1
    }
    lastStartOffset = currentStart

    offsetAttr.setOffset(first.startOffset, first.endOffset)
    typeAttr.setType(first.type)

    bufferedTokens.addAll(tokens)
    return true
  }

  override fun reset() {
    super.reset()
    bufferedTokens.clear()
    abbreviationToken = null
    lastStartOffset = -1
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
