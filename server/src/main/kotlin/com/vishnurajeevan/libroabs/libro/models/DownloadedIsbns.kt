package com.vishnurajeevan.libroabs.libro.models

import kotlinx.serialization.Serializable

@Serializable
data class DownloadedIsbns(
  val isbns: List<String>
)