package com.vishnurajeevan.libroabs.tracker

import com.vishnurajeevan.libroabs.audiobookshelf.cleanPersonName
import com.vishnurajeevan.libroabs.connector.TrackerCandidate

/** A tracker book judged to be the same as ours, and why. */
internal data class BookMatch(val candidate: TrackerCandidate, val score: Double, val reason: String)

/**
 * Picks the search result that is the book called [title] by [authors], or `null` when none is close enough to trust.
 * Titles are compared loosely (case, punctuation, a leading "The", subtitles and the tracker's alternative titles),
 * but an author must match: a book of the same name by someone else is never taken.
 */
internal fun bestMatch(title: String, authors: List<String>, candidates: List<TrackerCandidate>): BookMatch? {
  val ourAuthors = authors.map { personKey(cleanPersonName(it)) }.filter { it.isNotEmpty() }
  return candidates
    .mapNotNull { candidate ->
      val titleScore = (listOf(candidate.title) + candidate.alternativeTitles).maxOf { titleSimilarity(title, it) }
      val authorScore = candidate.authors.maxOfOrNull { theirs ->
        ourAuthors.maxOfOrNull { authorSimilarity(it, personKey(theirs.name)) } ?: 0.0
      } ?: 0.0
      if (titleScore < 0.85 || authorScore < 0.8) return@mapNotNull null
      // An omnibus that happens to share the title is a worse match than the book itself.
      val score = (titleScore * 0.6 + authorScore * 0.4) * if (candidate.compilation) 0.9 else 1.0
      BookMatch(candidate, score, describe(titleScore, authorScore))
    }
    .sortedWith(compareByDescending<BookMatch> { it.score }.thenByDescending { it.candidate.usersCount })
    .firstOrNull()
}

private fun describe(titleScore: Double, authorScore: Double): String {
  val titlePart = when {
    titleScore >= 1.0 -> "same title"
    titleScore >= 0.95 -> "same title apart from the subtitle"
    else -> "similar title"
  }
  val authorPart = if (authorScore >= 1.0) "same author" else "author's name written differently"
  return "$titlePart, $authorPart"
}

internal fun titleSimilarity(ours: String, theirs: String): Double {
  val a = titleKey(ours)
  val b = titleKey(theirs)
  if (a.isEmpty() || b.isEmpty()) return 0.0
  if (a == b) return 1.0
  val mainA = titleKey(mainTitle(ours))
  val mainB = titleKey(mainTitle(theirs))
  if (mainA.isNotEmpty() && mainA == mainB) return 0.95
  val wordsA = mainA.split(' ').toSet()
  val wordsB = mainB.split(' ').toSet()
  return (wordsA intersect wordsB).size.toDouble() / (wordsA union wordsB).size
}

/** Full name match, or the same surname and first initial ("J. R. R. Tolkien" and "John Ronald Reuel Tolkien"). */
internal fun authorSimilarity(a: String, b: String): Double {
  if (a.isEmpty() || b.isEmpty()) return 0.0
  if (a == b) return 1.0
  val wordsA = a.split(' ')
  val wordsB = b.split(' ')
  return if (wordsA.last() == wordsB.last() && wordsA.first().first() == wordsB.first().first()) 0.8 else 0.0
}

/** The title before any subtitle, series note or edition note. */
private fun mainTitle(title: String): String = title
  .substringBefore(':')
  .substringBefore(" - ")
  .replace(Regex("""\s*[(\[].*?[)\]]"""), "")

private fun titleKey(title: String): String = title
  .lowercase()
  .replace("&", " and ")
  .replace(Regex("""\s*[(\[].*?[)\]]"""), "")
  .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
  .trim()
  .removePrefix("the ")
  .removePrefix("a ")
  .removePrefix("an ")
  .trim()

private fun personKey(name: String): String = name
  .lowercase()
  .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
  .trim()
