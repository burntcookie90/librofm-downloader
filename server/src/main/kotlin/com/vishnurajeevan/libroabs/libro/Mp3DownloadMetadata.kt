package com.vishnurajeevan.libroabs.libro

import kotlinx.serialization.Serializable

@Serializable
data class Mp3DownloadMetadata(
  val parts: List<DownloadPart>,
  val tracks: List<Tracks>
)

@Serializable
data class DownloadPart(
  val url: String,
  val size_bytes: Long
)

@Serializable
data class Tracks(
  val number: Int,
  val chapter_title: String?,
)