package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.WhitespaceTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.util.Attribute
import org.apache.lucene.util.AttributeImpl
import org.apache.lucene.util.AttributeReflector


interface MultiTypeAttribute : Attribute {
  val typeFlags: BooleanArray   // indexed by FileTokenType.ordinal; size = FileTokenType.entries.size
  fun setTypes(types: Collection<FileTokenType>)
  fun hasType(type: FileTokenType): Boolean
  fun clearTypes(): MultiTypeAttribute
  fun isEmpty(): Boolean
  fun activeTypes(): List<FileTokenType>
}

@Suppress("unused")
class MultiTypeAttributeImpl : AttributeImpl(), MultiTypeAttribute {
  override val typeFlags: BooleanArray = BooleanArray(FileTokenType.entries.size)
  override fun setTypes(types: Collection<FileTokenType>): Unit = types.forEach { typeFlags[it.ordinal] = true }
  override fun hasType(type: FileTokenType): Boolean = typeFlags[type.ordinal]
  override fun clearTypes(): MultiTypeAttributeImpl = apply { typeFlags.fill(false) }
  override fun isEmpty(): Boolean = typeFlags.none { it }
  override fun activeTypes(): List<FileTokenType> =
    FileTokenType.entries.filter { typeFlags[it.ordinal] }
  override fun clear() { clearTypes() }
  override fun copyTo(target: AttributeImpl) {
    val t = target as MultiTypeAttribute
    t.clearTypes()
    t.setTypes(activeTypes())
  }
  override fun reflectWith(reflector: AttributeReflector) {
    reflector.reflect(MultiTypeAttribute::class.java, "types",
      activeTypes().joinToString(",") { it.name })
  }
}


class FileSearchAnalyzer : Analyzer() {
  override fun createComponents(fieldName: String): TokenStreamComponents {
    val tokenizer = WhitespaceTokenizer()
    val filter = FileSearchTokenFilter(tokenizer)
    return TokenStreamComponents(tokenizer, filter)
  }
}

class FileSearchTokenFilter(input: TokenStream) : TokenFilter(input) {
  private val termAttr = addAttribute(CharTermAttribute::class.java)
  private val posIncrAttr = addAttribute(PositionIncrementAttribute::class.java)
  private val offsetAttr: OffsetAttribute = addAttribute(OffsetAttribute::class.java)
  private val wordAttr = addAttribute(WordAttribute::class.java)
  private val multiTypeAttr = addAttribute(MultiTypeAttribute::class.java)

  private val pendingTokens = mutableListOf<PendingToken>()

  private data class PendingToken(
    val term: String,
    val multiTypes: Set<FileTokenType>,   // always non-empty; tokens with same (term, startOffset, endOffset) are merged
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
        wordAttr.wordIndex = token.wordIndex
        multiTypeAttr.clearTypes().setTypes(token.multiTypes)


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

      val segmentTokens = LinkedHashMap<Triple<String, Int, Int>, PendingToken>()
      val ngramTokens = mutableListOf<PendingToken>()

      fun addToSegment(token: PendingToken) {
        val key = Triple(token.term, token.startOffset, token.endOffset)
        val existing = segmentTokens[key]
        segmentTokens[key] = existing?.copy(multiTypes = existing.multiTypes + token.multiTypes) ?: token
      }

      var firstInSegment = true
      for (tokenInfo in identified) {
        val basePosIncr = if (firstInSegment) originalPosIncr else 1
        val absoluteTokenStart = originalStartOffset + tokenInfo.start
        val absoluteTokenEnd = originalStartOffset + tokenInfo.end

        if (tokenInfo.type == FileTokenType.FILENAME) {
          val subInfos = buildFilenameSubTokens(tokenInfo.text, absoluteTokenStart)
          var isFirst = true
          for (info in subInfos) {
            val posIncr = if (isFirst) basePosIncr else 0
            addToSegment(PendingToken(info.term, setOf(info.type), posIncr, info.start, info.end, currentWordIndex))
            isFirst = false
          }

          // Ngrams only for unstructured single-part lowercase words of length >= 4
          val filenameToken = subInfos.find { it.type == FileTokenType.FILENAME }
          val filenameParts = subInfos.filter { it.type == FileTokenType.FILENAME_PART }
          if (filenameParts.size == 1 && filenameToken != null
              && filenameParts[0].term == filenameToken.term
              && filenameToken.term.length >= 4
              && tokenInfo.text.none { it.isUpperCase() }) {
            val term = filenameParts[0].term
            val ngramTypes = setOf(FileTokenType.FILENAME_PART, FileTokenType.PATH_SEGMENT_PREFIX)
            var i = 0
            while (i + 2 <= term.length) {
              ngramTokens.add(PendingToken(
                term.substring(i, i + 2), ngramTypes,
                posIncr = 0,
                startOffset = absoluteTokenStart + i,
                endOffset = absoluteTokenStart + i + 2,
                wordIndex = currentWordIndex,
              ))
              i += 2
            }
          }
        }
        else {
          val termToAdd =
            if (tokenInfo.type == FileTokenType.PATH || tokenInfo.type == FileTokenType.PATH_SEGMENT) tokenInfo.text else tokenInfo.text.lowercase()
          addToSegment(PendingToken(termToAdd, setOf(tokenInfo.type), basePosIncr, absoluteTokenStart, absoluteTokenEnd, currentWordIndex))
        }
        firstInSegment = false
      }

      pendingTokens.addAll(segmentTokens.values)
      pendingTokens.addAll(ngramTokens)
    }
  }

  override fun reset() {
    super.reset()
    pendingTokens.clear()
    lastStartOffset = -1
    currentWordIndex = -1
  }

  private data class IdentifiedToken(val text: String, val type: FileTokenType, val start: Int, val end: Int)

  private fun identifyTokensWithOffsets(segment: String): List<IdentifiedToken> {
    val result = mutableListOf<IdentifiedToken>()
    var currentOffset = 0
    val parts = segment.split('/', '\\')

    // Always add the full segment as a PATH token first
    result.add(IdentifiedToken(segment, FileTokenType.PATH, 0, segment.length))

    for (i in 0 until parts.size - 1) {
      val part = parts[i]
      if (part.isNotEmpty()) {
        result.add(IdentifiedToken(part, FileTokenType.PATH_SEGMENT, currentOffset, currentOffset + part.length))
      }
      currentOffset += part.length + 1
    }

    val lastPart = parts.last()
    if (lastPart.isEmpty()) return result

    // If it's a multi-part path, the last part is also a PATH_SEGMENT token
    if (parts.size > 1) {
      result.add(IdentifiedToken(lastPart, FileTokenType.PATH_SEGMENT, currentOffset, currentOffset + lastPart.length))
    }

    val dotIndex = lastPart.lastIndexOf('.')
    if (dotIndex < 0) {
      result.add(IdentifiedToken(lastPart, FileTokenType.FILENAME, currentOffset, currentOffset + lastPart.length))
      // Also emit as FILETYPE since extension-less files like "java", "kt" should be searchable by type
      if (lastPart.isNotEmpty()) {
        result.add(IdentifiedToken(lastPart, FileTokenType.FILETYPE, currentOffset, currentOffset + lastPart.length))
      }
      // For single-component paths without extension (like "foo"), also emit as PATH_SEGMENT
      // so that searching for "foo" can match directory names in paths like "foo/bar/file.txt"
      if (parts.size == 1 && lastPart.isNotEmpty()) {
        result.add(IdentifiedToken(lastPart, FileTokenType.PATH_SEGMENT, currentOffset, currentOffset + lastPart.length))
      }
    }
    else if (dotIndex == 0) {
      // Hidden file like .gitignore
      result.add(IdentifiedToken(lastPart, FileTokenType.FILENAME, currentOffset, currentOffset + lastPart.length))
      val filetype = lastPart.substring(1)
      if (filetype.isNotEmpty()) {
        result.add(IdentifiedToken(filetype, FileTokenType.FILETYPE, currentOffset + 1, currentOffset + lastPart.length))
      }
    }
    else {
      val filename = lastPart.substring(0, dotIndex)
      val filetype = lastPart.substring(dotIndex + 1)
      result.add(IdentifiedToken(filename, FileTokenType.FILENAME, currentOffset, currentOffset + filename.length))
      if (filetype.isNotEmpty()) {
        result.add(IdentifiedToken(filetype, FileTokenType.FILETYPE, currentOffset + dotIndex + 1, currentOffset + lastPart.length))
      }
    }
    return result
  }
}


interface WordAttribute : Attribute {
  var wordIndex: Int
}

@Suppress("unused")
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
