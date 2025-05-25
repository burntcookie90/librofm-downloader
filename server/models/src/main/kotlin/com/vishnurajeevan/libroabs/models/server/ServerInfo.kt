package com.vishnurajeevan.libroabs.models.server

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
  val healthCheckHost: String,
  val healthCheckId: String,
) {
  fun prettyPrint(): String {
    return """
      |Server Info:
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
    """.trimMargin()
  }
}