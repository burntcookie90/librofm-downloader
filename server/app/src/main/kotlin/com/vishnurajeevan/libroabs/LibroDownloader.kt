package com.vishnurajeevan.libroabs

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.parameters.groups.cooccurring
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.varargValues
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import com.vishnurajeevan.libroabs.graph.AppComponent
import com.vishnurajeevan.libroabs.graph.create
import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.options.HardcoverOptionGroup
import com.vishnurajeevan.libroabs.options.HealthchecksIoOptionGroup

suspend fun main(args: Array<String>) = LibroDownloader().main(args)

class LibroDownloader : SuspendingCliktCommand("LibroFm Downloader") {
  private val port by option("--port")
    .int()
    .default(8080)

  private val dataDir by option("--data-dir")
    .default("/data")

  private val mediaDir by option("--media-dir")
    .default("/media")

  private val syncInterval by option("--sync-interval", envvar = "SYNC_INTERVAL")
    .choice("h", "d", "w")
    .default("d")

  private val dryRun by option("--dry-run", "-n", envvar = "DRY_RUN")
    .flag(default = false)

  private val renameChapters by option("--rename-chapters", envvar = "RENAME_CHAPTERS")
    .flag(default = false)

  private val writeTitleTag by option("--write-title-tag", envvar = "WRITE_TITLE_TAG")
    .flag(default = false)

  private val format: BookFormat by option("--format", envvar = "FORMAT")
    .enum<BookFormat>(ignoreCase = true)
    .default(BookFormat.MP3)

  private val parallelCount by option("--parallel-count", envvar = "PARALLEL_COUNT")
    .int()
    .restrictTo(
      min = 1,
      max = 3,
      clamp = true
    )
    .default(1)

  private val logLevel: ApplicationLogLevel by option("--log-level", envvar = "LOG_LEVEL")
    .enum<ApplicationLogLevel>(ignoreCase = true)
    .default(ApplicationLogLevel.NONE)

  private val limit by option("--limit", envvar = "LIMIT")
    .int()
    .default(-1)

  private val pathPattern by option("--path-pattern", envvar = "PATH_PATTERN")
    .default("FIRST_AUTHOR/BOOK_TITLE")

  private val healthChecksIoOptions by HealthchecksIoOptionGroup().cooccurring()
  private val hardcoverOptions by HardcoverOptionGroup().cooccurring()
  private val skipTrackingIsbns: List<String> by option("--skip-tracking-isbns", envvar = "SKIP_TRACKING_ISBNS")
    .varargValues()
    .default(emptyList())

  private val audioQuality: String by option("--audio-quality", envvar = "AUDIO_QUALITY")
    .default("128k")

  private val ffmpegPath by option("--ffmpeg-path")
    .default("/usr/bin/ffmpeg")

  private val ffprobePath by option("--ffprobe-path")
    .default("/usr/bin/ffprobe")

  private val libroFmUsername by option("--libro-fm-username", envvar = "LIBRO_FM_USERNAME")
    .required()

  private val libroFmPassword by option("--libro-fm-password", envvar = "LIBRO_FM_PASSWORD")
    .required()

  override suspend fun run() {
    val serverInfo = ServerInfo(
      libroUserName = libroFmUsername,
      libroPassword = libroFmPassword,
      port = port,
      dataDir = dataDir,
      mediaDir = mediaDir,
      syncInterval = syncInterval,
      parallelCount = parallelCount,
      dryRun = dryRun,
      renameChapters = renameChapters,
      writeTitleTag = writeTitleTag,
      format = format,
      logLevel = logLevel,
      limit = limit,
      pathPattern = pathPattern,
      healthCheckHost = healthChecksIoOptions?.healthCheckHost.orEmpty(),
      healthCheckId = healthChecksIoOptions?.healthCheckId,
      trackerToken = hardcoverOptions?.hardcoverToken,
      trackerEndpoint = hardcoverOptions?.hardcoverEndpoint.orEmpty(),
      ffmpegPath = ffmpegPath,
      ffprobePath = ffprobePath,
      audioQuality = audioQuality,
      skipTrackingIsbns = skipTrackingIsbns,
    )
    println(serverInfo.prettyPrint())

    AppComponent::class.create(serverInfo).app.run()
  }
}

