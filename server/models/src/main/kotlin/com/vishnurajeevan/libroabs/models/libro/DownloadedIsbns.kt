package com.vishnurajeevan.libroabs.models.libro

import kotlinx.serialization.Serializable

@Serializable
data class DownloadedIsbns(
  val isbns: List<String>
)