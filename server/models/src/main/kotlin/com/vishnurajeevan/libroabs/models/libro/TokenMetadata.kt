package com.vishnurajeevan.libroabs.models.libro

import dev.zacsweers.redacted.annotations.Redacted
import kotlinx.serialization.Serializable

@Serializable
data class TokenMetadata(
  @Redacted val access_token: String? = null,
)