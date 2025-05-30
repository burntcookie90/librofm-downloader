package com.vishnurajeevan.libroabs.storage.models

import com.vishnurajeevan.libroabs.models.libro.Book
import kotlinx.serialization.Serializable

@Serializable
data class LibraryMetadata(
  val audiobooks: List<Book> = emptyList(),
  val error: String? = null
)