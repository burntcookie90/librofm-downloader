package com.vishnurajeevan.libroabs.audiobookshelf

import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsBookMetadata
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsChapter
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsField
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsItemMetadata
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsLibraryItem
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.server.ui.MetadataChange

private const val NONE = "—"

/** The key of the chapter names change, alongside the [AbsField] names. */
internal const val CHAPTERS = "CHAPTERS"

/** Everything that can be chosen when applying, in display order. */
internal val absFieldChoices: List<Pair<String, String>> =
  AbsField.entries.filter { it != AbsField.LANGUAGE }.map { it.name to it.label } + (CHAPTERS to "Chapter names")

/**
 * The fields syncing would change: those libro.fm has a value for that differs from Audiobookshelf's. Fields libro.fm
 * doesn't know are never sent, so they never show up here.
 */
internal fun metadataChanges(current: AbsItemMetadata, target: AbsBookMetadata): List<MetadataChange> = buildList {
  fun change(field: AbsField, before: String, after: String) =
    add(MetadataChange(field.label, before.ifBlank { NONE }.shorten(), after.shorten(), field.name))

  fun text(field: AbsField, before: String?, after: String?, same: (String, String) -> Boolean = { a, b -> a.trim() == b.trim() }) {
    if (after.isNullOrBlank()) return
    if (before != null && same(before, after)) return
    change(field, before.orEmpty(), after)
  }

  fun list(field: AbsField, before: List<String>, after: List<String>, ordered: Boolean) {
    if (after.isEmpty()) return
    val b = before.map(String::trim)
    val a = after.map(String::trim)
    if (if (ordered) b != a else b.toSet() != a.toSet()) change(field, before.joinToString(", "), after.joinToString(", "))
  }

  text(AbsField.TITLE, current.title, target.title)
  text(AbsField.SUBTITLE, current.subtitle, target.subtitle)
  list(AbsField.AUTHORS, current.authors.map { it.name }, target.authors, ordered = true)
  list(AbsField.NARRATORS, current.narrators, target.narrators, ordered = true)
  target.series?.let { series ->
    val after = series + (target.seriesSequence?.let { " #$it" } ?: "")
    val before = current.series.joinToString(", ") { it.name + (it.sequence?.let { s -> " #$s" } ?: "") }
    if (before != after) change(AbsField.SERIES, before, after)
  }
  list(AbsField.GENRES, current.genres, target.genres, ordered = false)
  text(AbsField.PUBLISHER, current.publisher, target.publisher)
  text(AbsField.PUBLISHED, current.publishedDate ?: current.publishedYear, target.publishedDate)
  text(AbsField.DESCRIPTION, current.description?.let(::plainText), target.description?.let(::plainText))
  text(AbsField.ISBN, current.isbn, target.isbn) { a, b -> a.filter(Char::isLetterOrDigit) == b.filter(Char::isLetterOrDigit) }
}

/** Renaming chapters to libro.fm's names, when Audiobookshelf has the same number of them. */
internal fun chapterChange(current: List<AbsChapter>, titles: List<String>?): MetadataChange? {
  if (titles == null || current.size != titles.size || current.map { it.title } == titles) return null
  fun preview(names: List<String>) = names.take(3).joinToString(", ") + if (names.size > 3) ", …" else ""
  return MetadataChange("Chapter names", preview(current.map { it.title }), preview(titles), CHAPTERS)
}

/** Audiobookshelf's own track-numbered chapter names, which libro.fm's names should replace. */
internal fun List<AbsChapter>.lookGeneric(): Boolean = any { generic.containsMatchIn(it.title) }

private val generic = Regex("""(?i)\btrack\s*\d+\b|^\s*\d+\s*$""")

/**
 * libro.fm's metadata, spelled the way the library already spells it: a series or author that differs only by a
 * leading "The", case or punctuation ("Wheel of Time" for "The Wheel of Time") keeps the library's name, so syncing
 * never splits an existing series or author in two.
 */
internal fun AbsBookMetadata.spelledLike(items: List<AbsLibraryItem>): AbsBookMetadata {
  val series = items.flatMap { item ->
    item.media.metadata.series.map { it.name } +
      item.media.metadata.seriesName?.split(", ")?.map { it.substringBefore(" #") }.orEmpty()
  }
  val authors = items.flatMap { item ->
    item.media.metadata.authors.map { it.name } + item.media.metadata.authorName?.split(", ").orEmpty()
  }
  fun List<String>.spelling(name: String) = firstOrNull { it.isNotBlank() && it.nameKey() == name.nameKey() } ?: name
  return copy(
    series = this.series?.let { series.spelling(it) },
    authors = this.authors.map { authors.spelling(it) },
  )
}

private fun String.nameKey() = lowercase().removePrefix("the ").filter(Char::isLetterOrDigit)

/** libro.fm puts credentials in people's names ("Dr Georgia Ede", "Daniel J. Siegel, MD"), which aren't part of them. */
internal fun cleanPersonName(name: String): String = name.trim()
  .replace(Regex("""^(?i)dr\.?\s+"""), "")
  .replace(Regex(""",\s*(?:MD|M\.D\.|PhD|Ph\.D\.|DPhil|PsyD|EdD|MA|MS|MSc|BSc|RN|DO|MPH|MBA|LCSW|LMFT)\.?$"""), "")
  .trim()

/**
 * Finds [book] among Audiobookshelf's [items], returning how it was matched. An item with the book's ISBN wins, then
 * the item in the book's download folder ([absPath], when known), then one with the same title and author.
 */
internal fun matchItem(book: Book, items: List<AbsLibraryItem>, absPath: String?): Pair<AbsLibraryItem, String>? {
  val isbn = book.isbn.filter(Char::isLetterOrDigit)
  items.firstOrNull { it.media.metadata.isbn?.filter(Char::isLetterOrDigit) == isbn }?.let { return it to "ISBN" }
  absPath?.let { path -> items.firstOrNull { it.path.trimEnd('/') == path.trimEnd('/') } }?.let { return it to "download folder" }

  val titles = setOf(book.title.comparable(), book.title.substringBefore(':').comparable())
  val authors = book.authors.map { cleanPersonName(it).comparable() }.filter { it.isNotEmpty() }
  return items.firstOrNull { item ->
    val metadata = item.media.metadata
    val title = metadata.title.orEmpty()
    val author = (metadata.authorName ?: metadata.authors.joinToString { it.name }).comparable()
    (title.comparable() in titles || title.substringBefore(':').comparable() in titles) &&
      authors.any { it in author }
  }?.let { it to "title and author" }
}

private fun String.comparable() = lowercase().filter(Char::isLetterOrDigit)

private fun plainText(html: String) = html
  .replace(Regex("(?i)<br\\s*/?>|</p>"), " ")
  .replace(Regex("<[^>]+>"), "")
  .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
  .replace("&nbsp;", " ")
  .replace(Regex("\\s+"), " ")
  .trim()

private fun String.shorten(max: Int = 240) = if (length <= max) this else take(max - 1).trimEnd() + "…"
