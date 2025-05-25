package com.vishnurajeevan.libroabs.models.libro

import kotlinx.serialization.Serializable

@Serializable
data class LibraryMetadata(
  val audiobooks: List<Book> = emptyList(),
  val error: String? = null
)