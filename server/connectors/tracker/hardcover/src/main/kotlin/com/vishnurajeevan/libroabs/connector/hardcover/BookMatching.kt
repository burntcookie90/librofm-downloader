package com.vishnurajeevan.libroabs.connector.hardcover

import java.text.Normalizer

/**
 * Confirms that a candidate returned by Hardcover's search endpoint really is the book we asked for.
 *
 * Search is fuzzy and ranked: it will happily return near misses, other books in a series, or a
 * different edition of a similar title. That is the right behaviour for a human picking from a list
 * and the wrong behaviour for us, because a match here leads to `insert_edition` writing into
 * Hardcover's shared book database. Attaching a Libro.fm audiobook edition to the wrong book is worse
 * than not creating one at all.
 *
 * So we let search do the recall work and then verify precision locally. Normalization closes the
 * gap on formatting differences that the old exact `_eq` filter could never match — case, accents,
 * punctuation, `&` vs `and`, "J.R.R." vs "J R R" — without loosening what counts as the same book.
 */
internal object BookMatching {

  /** Picks the first candidate that passes verification, preserving search's ranking. */
  fun <T> bestMatch(
    title: String,
    author: String,
    candidates: List<T>,
    titleOf: (T) -> String?,
    authorsOf: (T) -> List<String>,
  ): T? = candidates.firstOrNull { candidate ->
    val candidateTitle = titleOf(candidate) ?: return@firstOrNull false
    titleMatches(title, candidateTitle) && authorMatches(author, authorsOf(candidate))
  }

  /**
   * Titles match when they normalize to the same string, or when one is the other followed by a
   * subtitle. Libro.fm and Hardcover disagree constantly about whether "A Novel" or a series suffix
   * belongs in the title; requiring the shorter to end on a word boundary of the longer keeps
   * "Dune" from matching "Dune Messiah".
   */
  internal fun titleMatches(libroTitle: String, trackerTitle: String): Boolean {
    val libro = normalize(libroTitle)
    val tracker = normalize(trackerTitle)
    if (libro.isEmpty() || tracker.isEmpty()) return false
    if (libro == tracker) return true

    val (shorter, longer) = if (libro.length < tracker.length) libro to tracker else tracker to libro
    return longer.startsWith("$shorter ") && SUBTITLE_SEPARATORS.any {
      trackerTitle.contains(it) || libroTitle.contains(it)
    }
  }

  /**
   * Authors match when the normalized name tokens of one are a subset of the other's. That accepts a
   * middle name or initial being present on one side and absent on the other, and is order
   * independent so "King, Stephen" matches "Stephen King", while still requiring every token of the
   * shorter name to appear.
   */
  internal fun authorMatches(libroAuthor: String, trackerAuthors: List<String>): Boolean {
    val libroTokens = normalize(libroAuthor).split(" ").filter { it.isNotEmpty() }.toSet()
    if (libroTokens.isEmpty()) return false

    return trackerAuthors.any { trackerAuthor ->
      val trackerTokens = normalize(trackerAuthor).split(" ").filter { it.isNotEmpty() }.toSet()
      if (trackerTokens.isEmpty()) return@any false
      libroTokens.containsAll(trackerTokens) || trackerTokens.containsAll(libroTokens)
    }
  }

  /** Lowercase, strip accents and punctuation, spell out `&`, collapse whitespace. */
  internal fun normalize(value: String): String = Normalizer
    .normalize(value.lowercase(), Normalizer.Form.NFD)
    .replace(DIACRITICS, "")
    .replace("&", " and ")
    .replace(NON_ALPHANUMERIC, " ")
    .trim()
    .replace(WHITESPACE, " ")

  private val DIACRITICS = "\\p{Mn}+".toRegex()
  private val NON_ALPHANUMERIC = "[^a-z0-9]+".toRegex()
  private val WHITESPACE = "\\s+".toRegex()
  private val SUBTITLE_SEPARATORS = listOf(":", " - ", "(")
}
