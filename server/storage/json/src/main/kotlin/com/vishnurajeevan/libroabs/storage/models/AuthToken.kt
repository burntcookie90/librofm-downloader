package com.vishnurajeevan.libroabs.storage.models

import dev.zacsweers.redacted.annotations.Redacted
import kotlinx.serialization.Serializable

@Serializable
data class AuthToken(
  @Redacted val token: String? = null
)
