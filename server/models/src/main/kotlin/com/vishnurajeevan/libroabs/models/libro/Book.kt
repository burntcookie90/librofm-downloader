package com.vishnurajeevan.libroabs.models.libro

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Book(
  val title: String,
  val authors: List<String>,
  val isbn: String,
  val cover_url: String,
  val audiobook_info: BookInfo,
  val publisher: String,
  val publication_date: Instant,
  val description: String,
  val genres: List<Genre>,
  val subtitle: String? = null,
  val series: String? = null,
  val series_num: Int? = null
)

@Serializable
data class BookInfo(
  val narrators: List<String>,
  val duration: Int, // Total duration in seconds
  val track_count: Int,
  val pdf_extras: List<PdfExtra> = emptyList()
)

@Serializable
data class Genre(
  val name: String
)

@Serializable
data class Chapter(
  val title: String,
  val startTimeMs: Long,
  val endTimeMs: Long
)

@Serializable
data class PdfExtra(
  val filename: String,
)

