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
)

@Serializable
data class AbsItemMetadata(
  val title: String? = null,
  val isbn: String? = null,
)

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
internal data class ItemsResponse(val results: List<AbsLibraryItem> = emptyList())

@Serializable
internal data class SearchResponse(val book: List<SearchHit> = emptyList())

@Serializable
internal data class SearchHit(val libraryItem: AbsLibraryItem)

@Serializable
internal data class WatchedPathUpdate(val libraryId: String, val path: String, val type: String)

@Serializable
internal data class ChaptersUpdate(val chapters: List<AbsChapter>)
