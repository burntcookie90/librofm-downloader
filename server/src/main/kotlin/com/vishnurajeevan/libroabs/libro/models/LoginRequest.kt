package com.vishnurajeevan.libroabs.libro.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest @OptIn(ExperimentalSerializationApi::class) constructor(
  @EncodeDefault val grant_type: String = "password",
  val username: String,
  val password: String
)