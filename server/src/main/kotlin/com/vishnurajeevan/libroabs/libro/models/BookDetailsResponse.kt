package com.vishnurajeevan.libroabs.libro.models

import kotlinx.serialization.Serializable

@Serializable
data class BookDetailsResponse(
  val data: BookDetailsInnerResponse
)

@Serializable
data class BookDetailsInnerResponse(
  val audiobook: Book
)