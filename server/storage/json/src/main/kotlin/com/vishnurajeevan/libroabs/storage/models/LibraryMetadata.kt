package com.vishnurajeevan.libroabs.storage.models

import com.vishnurajeevan.libroabs.models.libro.Book
import kotlinx.serialization.Serializable

@Serializable
data class LibraryMetadata(
  val page: Int = -1,
  @Suppress("PropertyName") val total_pages: Int = 0,
  val audiobooks: List<Book> = emptyList(),
  val error: String? = null
)