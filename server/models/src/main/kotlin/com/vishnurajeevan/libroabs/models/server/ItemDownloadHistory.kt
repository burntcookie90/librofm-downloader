package com.vishnurajeevan.libroabs.models.server

import kotlinx.serialization.Serializable

@Serializable
data class ItemDownloadHistory(
  val isbn: String,
  val format: DownloadedFormat,
  val path: String,
  val hasPdfDownloaded: Boolean,
)
