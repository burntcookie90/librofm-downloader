package com.vishnurajeevan.libroabs.models.server

import dev.zacsweers.redacted.annotations.Redacted
import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(
  val libroUserName: String,
  @Redacted val libroPassword: String,
  val port: Int,
  val dataDir: String,
  val mediaDir: String,
  val syncInterval: String,
  val parallelCount: Int,
  val dryRun: Boolean,
  val renameChapters: Boolean,
  val writeTitleTag: Boolean,
  val format: BookFormat,
  val logLevel: ApplicationLogLevel,
  val limit: Int,
  val pathPattern: String,
  val healthCheckHost: String,
  val healthCheckId: String?,
  @Redacted val trackerToken: String?,
  val trackerEndpoint: String,
  val ffmpegPath: String,
  val ffprobePath: String,
  val audioQuality: String,
  val skipTrackingIsbns: List<String>,
) {
  fun prettyPrint(): String {
    return """
      |Server Info:
      |  Libro.fm Username: $libroUserName
      |  Port: $port
      |  Sync Interval: $syncInterval
      |  Parallel Count: $parallelCount
      |  Dry Run: $dryRun
      |  Rename Chapters: $renameChapters
      |  Write Title Tag: $writeTitleTag
      |  Format: $format
      |  Log Level: $logLevel
      |  Limit: $limit
      |  Path Pattern: $pathPattern
      |  Health Check Host: $healthCheckHost
      |  Health Check ID: $healthCheckId
      |  Tracker Enabled: ${!trackerToken.isNullOrEmpty()} 
    """.trimMargin()
  }
}