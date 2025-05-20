package com.vishnurajeevan.libroabs.libro.models.storage

import kotlinx.serialization.Serializable

@Serializable
data class AuthToken(
  val token: String? = null
)
