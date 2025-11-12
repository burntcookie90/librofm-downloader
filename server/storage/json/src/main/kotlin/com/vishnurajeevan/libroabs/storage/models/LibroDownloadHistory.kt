package com.vishnurajeevan.libroabs.storage.models

import com.vishnurajeevan.libroabs.models.server.DownloadedFormat
import kotlinx.serialization.Serializable

@Serializable
data class LibroDownloadHistory(
  val books: Map<String, LibroDownloadItem> = emptyMap()
)

@Serializable
data class LibroDownloadItem(
  val isbn: String,
  val format: DownloadedFormat,
  val path: String,
)

