package com.vishnurajeevan.libroabs.models

import kotlinx.serialization.Serializable

@Serializable
data class LibroDownloadHistory(
  val books: Map<String, LibroDownloadItem> = emptyMap()
)

@Serializable
data class LibroDownloadItem(
  val isbn: String,
  val format: Format,
  val path: String,
)

/**
 * Different from [BookFormat] because we dont want to save the fallback
 */
@Serializable
enum class Format() {
  M4B, M4B_CONVERTED, MP3
}