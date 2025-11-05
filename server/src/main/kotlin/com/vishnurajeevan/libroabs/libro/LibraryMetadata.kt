package com.vishnurajeevan.libroabs.libro

import kotlinx.serialization.Serializable

@Serializable
data class LibraryMetadata(
  val page: Int,
  @Suppress("PropertyName") val total_pages: Int,
  val audiobooks: List<Book> = emptyList(),
  val error: String? = null
)