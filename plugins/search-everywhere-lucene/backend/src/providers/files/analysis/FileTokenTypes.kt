package com.intellij.searchEverywhereLucene.backend.providers.files.analysis

enum class FileTokenType(val type: String) {
  PATH("path"),
  PATH_SEGMENT("pathSegment"),
  PATH_SEGMENT_PREFIX("pathSegmentPrefix"),
  FILENAME("filename"),
  FILENAME_PART("filenamePart"),
  FILENAME_ABBREVIATION("filenameAbbreviation"),
  FILETYPE("filetype");

  companion object {
    private val byType = entries.associateBy { it.type }
  }
}
