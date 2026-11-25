package com.vishnurajeevan.libroabs.storage.models

import dev.zacsweers.redacted.annotations.Redacted
import kotlinx.serialization.Serializable

@Serializable
data class AuthToken(
  @Redacted val token: String? = null,
  /** The libro.fm account the token belongs to; null for tokens saved before this was recorded. */
  val username: String? = null,
)
