package com.vishnurajeevan.libroabs.models

import com.vishnurajeevan.libroabs.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.BookFormat
import dev.zacsweers.redacted.annotations.Redacted
import kotlinx.serialization.Serializable

@Serializable
data class ServerInfo(
  val port: Int,
  val dataDir: String,
  val mediaDir: String,
  val syncInterval: String,
  val dryRun: Boolean,
  val renameChapters: Boolean,
  val writeTitleTag: Boolean,
  val format: BookFormat,
  val parallelCount: Int,
  val logLevel: ApplicationLogLevel,
  val limit: Int,
  val pathPattern: String,
  val healthCheckHost: String?,
  val healthCheckId: String?,
  val hardcoverToken: String?,
  val hardcoverEndpoint: String?,
  val skipTrackingIsbns: List<String>,
  val audioQuality: String,
  val ffmpegPath: String,
  val ffprobePath: String,
  val libroFmUsername: String,
  @Redacted val libroFmPassword: String,
) {
  fun formattedString(): String {
    return """
      ServerInfo(
        port=$port,
        dataDir='$dataDir',
        mediaDir='$mediaDir',
        syncInterval='$syncInterval',
        dryRun=$dryRun,
        renameChapters=$renameChapters,
        writeTitleTag=$writeTitleTag,
        format=$format,
        parallelCount=$parallelCount,
        logLevel=$logLevel,
        limit=$limit,
        pathPattern='$pathPattern',
        healthCheckHost='$healthCheckHost',
        healthCheckId='$healthCheckId',
        skipTrackingIsbns=$skipTrackingIsbns,
        audioQuality='$audioQuality',
        ffmpegPath='$ffmpegPath',
        ffprobePath='$ffprobePath',
        libroFmUsername='$libroFmUsername',
      )
    """.trimIndent()
  }
}