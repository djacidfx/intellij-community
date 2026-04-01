// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.searchEverywhereMl.typos.models

import com.intellij.searchEverywhereMl.typos.SearchEverywhereStringToken
import com.intellij.searchEverywhereMl.typos.splitText
import java.util.regex.Pattern

private val alphabeticPattern = Pattern.compile("^[a-zA-Z]+$")

internal class PhrasePrefixIndex private constructor(
  private val root: Node,
) {
  fun hasPrefix(query: String): Boolean {
    if (query.isBlank()) return false

    var current = root
    for (char in query) {
      current = current.children[char] ?: return false
    }
    return true
  }

  companion object {
    fun create(phrases: Iterable<String>): PhrasePrefixIndex {
      val root = Node()
      for (phrase in phrases) {
        if (phrase.isBlank()) continue

        for (startIndex in phrase.indices) {
          if (phrase[startIndex] == ' ') continue
          if (startIndex > 0 && phrase[startIndex - 1] != ' ') continue

          var current = root
          for (index in startIndex until phrase.length) {
            current = current.children.getOrPut(phrase[index]) { Node() }
          }
        }
      }
      return PhrasePrefixIndex(root)
    }
  }

  private class Node {
    val children = HashMap<Char, Node>()
  }
}

internal fun tokenizeTextForTypoLookup(text: CharSequence): List<String> {
  return splitText(text)
    .filterIsInstance<SearchEverywhereStringToken.Word>()
    .map { it.value.lowercase() }
    .filter { alphabeticPattern.matcher(it).matches() }
    .toList()
}

internal fun normalizeTextForPrefixLookup(text: CharSequence): String? {
  return tokenizeTextForTypoLookup(text)
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" ")
}

internal fun normalizeCorpusSentenceForPrefixLookup(sentence: List<String>): String? {
  return sentence
    .takeIf { it.isNotEmpty() }
    ?.joinToString(" ")
}
