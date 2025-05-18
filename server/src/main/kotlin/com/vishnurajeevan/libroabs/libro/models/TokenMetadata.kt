package com.vishnurajeevan.libroabs.libro.models

import kotlinx.serialization.Serializable

@Serializable
data class TokenMetadata(
  val access_token: String? = null,
)