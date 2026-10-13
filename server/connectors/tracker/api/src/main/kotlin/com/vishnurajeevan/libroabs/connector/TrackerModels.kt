package com.vishnurajeevan.libroabs.connector

import kotlinx.datetime.LocalDate

/** A search result: a book the tracker knows, to be matched against ours. */
data class TrackerCandidate(
  val id: String,
  val title: String,
  val alternativeTitles: List<String> = emptyList(),
  val authors: List<ConnectorContributor> = emptyList(),
  /** An omnibus or box set rather than a single book. */
  val compilation: Boolean = false,
  val usersCount: Int = 0,
  val releaseYear: Int? = null,
  /** Where the book can be seen on the tracker's site. */
  val url: String? = null,
)

/** What the tracker can be told about an audiobook edition beyond its title, ISBN and authors. */
data class EditionExtras(
  val audioSeconds: Int? = null,
  val narrators: List<String> = emptyList(),
  val publisher: String? = null,
)

enum class ReadingStatus { WANT_TO_READ, CURRENTLY_READING, READ, PAUSED, DID_NOT_FINISH, IGNORED }

/** A book on the user's shelves. */
data class TrackerUserBook(
  val id: String,
  val bookId: String,
  val status: ReadingStatus?,
  val editionId: String?,
  /** Newest first. */
  val reads: List<TrackerRead>,
)

/** One time through a book. */
data class TrackerRead(
  val id: String?,
  val startedAt: LocalDate?,
  val finishedAt: LocalDate?,
  val progressSeconds: Int?,
  val editionId: String?,
)
