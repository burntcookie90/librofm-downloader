package com.vishnurajeevan.libroabs.connector.audiobookshelf

import kotlinx.serialization.Serializable

@Serializable
data class AbsLibrary(
  val id: String,
  val name: String,
  val mediaType: String,
  val folders: List<AbsFolder> = emptyList(),
)

@Serializable
data class AbsFolder(
  val id: String,
  val fullPath: String,
)

@Serializable
data class AbsLibraryItem(
  val id: String,
  /** Where the item lives, as Audiobookshelf sees its own disk. */
  val path: String,
  val media: AbsMedia = AbsMedia(),
)

@Serializable
data class AbsMedia(
  val metadata: AbsItemMetadata = AbsItemMetadata(),
  val chapters: List<AbsChapter> = emptyList(),
  val coverPath: String? = null,
)

/**
 * A book's details. Lists give the `*Name` summaries; a single expanded item gives [authors], [narrators] and
 * [series] in full.
 */
@Serializable
data class AbsItemMetadata(
  val title: String? = null,
  val subtitle: String? = null,
  val authors: List<AbsNamed> = emptyList(),
  val authorName: String? = null,
  val narrators: List<String> = emptyList(),
  val series: List<AbsSeriesRef> = emptyList(),
  val seriesName: String? = null,
  val genres: List<String> = emptyList(),
  val publishedYear: String? = null,
  val publishedDate: String? = null,
  val publisher: String? = null,
  val description: String? = null,
  val isbn: String? = null,
  val asin: String? = null,
)

@Serializable
data class AbsNamed(val name: String)

/** What Audiobookshelf has for an item, as metadata it could be given back (to write its `metadata.json`). */
fun AbsItemMetadata.toBookMetadata() = AbsBookMetadata(
  title = title.orEmpty(),
  subtitle = subtitle,
  authors = authors.map { it.name },
  narrators = narrators,
  series = series.firstOrNull()?.name,
  seriesSequence = series.firstOrNull()?.sequence,
  genres = genres,
  publisher = publisher,
  publishedDate = publishedDate,
  publishedYearOverride = publishedYear,
  description = description,
  isbn = isbn,
)

@Serializable
data class AbsSeriesRef(val name: String, val sequence: String? = null)

@Serializable
data class AbsChapter(
  val id: Int,
  val start: Double,
  val end: Double,
  val title: String,
)

@Serializable
internal data class LibrariesResponse(val libraries: List<AbsLibrary>)

@Serializable
internal data class ItemsResponse(val results: List<AbsLibraryItem> = emptyList(), val total: Int = 0)

@Serializable
internal data class SearchResponse(val book: List<SearchHit> = emptyList())

@Serializable
internal data class SearchHit(val libraryItem: AbsLibraryItem)

@Serializable
internal data class WatchedPathUpdate(val libraryId: String, val path: String, val type: String)

@Serializable
internal data class ChaptersUpdate(val chapters: List<AbsChapter>)
