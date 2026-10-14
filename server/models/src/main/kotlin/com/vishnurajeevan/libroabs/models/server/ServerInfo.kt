package com.vishnurajeevan.libroabs.models.server

import dev.zacsweers.redacted.annotations.Redacted
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
  val downloadExtras: Boolean,
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
  val hardcoverSyncMode: TrackerSyncMode,
  val webhookUrls: List<String> = emptyList(),
  val libroFmHeaders: Map<String, String> = emptyMap(),
  /** When set, the web UI and API require this password. */
  @Redacted val webUiPassword: String? = null,
  /** Adds every downloaded book to Audiobookshelf when set. */
  val audiobookshelf: AudiobookshelfConfig? = null,
  /** Writes `metadata.json` and `cover.jpg` next to each book even without [audiobookshelf]. */
  val writeAbsMetadata: Boolean = false,
  /** Sends Audiobookshelf listening progress to Hardcover on every sync. */
  val hardcoverSyncProgress: Boolean = false,
  /** How often Hardcover is synced between library syncs; [Duration.ZERO] syncs it only with the library. */
  val hardcoverSyncInterval: Duration = 30.minutes,
) {
  val writesAbsMetadataFiles: Boolean get() = writeAbsMetadata || audiobookshelf != null

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
      |  Download Extras: $downloadExtras
      |  Log Level: $logLevel
      |  Limit: $limit
      |  Path Pattern: $pathPattern
      |  Health Check Host: $healthCheckHost
      |  Health Check: ${if (healthCheckId.isNullOrEmpty()) "Disabled" else "Enabled"}
      |  Tracker Enabled: ${!trackerToken.isNullOrEmpty()} 
      |  Tracker sync mode: $hardcoverSyncMode
      |  Tracker progress sync: $hardcoverSyncProgress
      |  Tracker sync interval: ${if (hardcoverSyncInterval == Duration.ZERO) "with library sync" else hardcoverSyncInterval}
      |  Webhooks: ${webhookUrls.map(::webhookHost)}
      |  LibroFM Headers: ${libroFmHeaders.keys}
      |  Web UI Password Protected: ${!webUiPassword.isNullOrEmpty()}
      |  Audiobookshelf: ${audiobookshelf?.let { "${webhookHost(it.url)} (${it.mode})" } ?: "Disabled"}
      |  Audiobookshelf Metadata Files: $writesAbsMetadataFiles
    """.trimMargin()
  }
}