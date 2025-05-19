package com.vishnurajeevan.libroabs.libro.models

import kotlinx.serialization.Serializable

@Serializable
data class LibraryMetadata(
  val audiobooks: List<Book> = emptyList(),
  val error: String? = null
)