package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.core.KeywordTokenizer
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
    return TokenStreamComponents(ReversePathHierarchyTokenizer('.', ReversePathHierarchyTokenizer.DEFAULT_SKIP))
  }
}

