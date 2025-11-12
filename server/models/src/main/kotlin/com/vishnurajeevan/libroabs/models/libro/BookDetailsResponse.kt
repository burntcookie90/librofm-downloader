package com.vishnurajeevan.libroabs.models.libro

import kotlinx.serialization.Serializable

@Serializable
data class BookDetailsResponse(
  val data: BookDetailsInnerResponse
)

@Serializable
data class BookDetailsInnerResponse(
  val audiobook: Book
)