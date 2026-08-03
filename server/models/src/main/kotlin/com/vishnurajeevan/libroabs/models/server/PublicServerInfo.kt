package com.vishnurajeevan.libroabs.models.server

import kotlinx.serialization.Serializable

/**
 * The subset of [ServerInfo] that is safe to hand to an HTTP caller.
 *
 * [ServerInfo] itself must never be serialized to a response. It is `@Serializable` and carries
 * `libroPassword` and `trackerToken`; the `@Redacted` annotations on those fields only rewrite
 * `toString()`, they do not affect kotlinx serialization. Responding with the whole object would put
 * the user's libro.fm password and Hardcover token in the response body of an unauthenticated
 * endpoint bound to 0.0.0.0.
 */
@Serializable
data class PublicServerInfo(
  val libroUserName: String,
  val port: Int,
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
  val healthCheckConfigured: Boolean,
  val trackerEnabled: Boolean,
  val trackerSyncMode: TrackerSyncMode,
  val webhooksConfigured: Int,
)

fun ServerInfo.toPublicServerInfo() = PublicServerInfo(
  libroUserName = libroUserName,
  port = port,
  syncInterval = syncInterval,
  parallelCount = parallelCount,
  dryRun = dryRun,
  renameChapters = renameChapters,
  writeTitleTag = writeTitleTag,
  format = format,
  downloadExtras = downloadExtras,
  logLevel = logLevel,
  limit = limit,
  pathPattern = pathPattern,
  healthCheckConfigured = !healthCheckId.isNullOrEmpty(),
  trackerEnabled = !trackerToken.isNullOrEmpty(),
  trackerSyncMode = hardcoverSyncMode,
  webhooksConfigured = webhookUrls.size,
)
