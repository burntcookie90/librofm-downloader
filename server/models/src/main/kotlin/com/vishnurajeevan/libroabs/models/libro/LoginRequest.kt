package com.vishnurajeevan.libroabs.models.libro

import dev.zacsweers.redacted.annotations.Redacted
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest @OptIn(ExperimentalSerializationApi::class) constructor(
  @EncodeDefault val grant_type: String = "password",
  val username: String,
  @Redacted val password: String
)