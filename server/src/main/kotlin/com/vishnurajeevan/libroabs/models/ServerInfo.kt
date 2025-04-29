package com.vishnurajeevan.libroabs.models

import com.vishnurajeevan.libroabs.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.BookFormat
import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(
  val port: Int,
  val syncInterval: String,
  val parallelCount: Int,
  val dryRun: Boolean,
  val renameChapters: Boolean,
  val writeTitleTag: Boolean,
  val format: BookFormat,
  val logLevel: ApplicationLogLevel,
  val limit: Int,
  val pathPattern: String,
)