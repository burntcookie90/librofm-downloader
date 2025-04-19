package com.vishnurajeevan.libroabs

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.vishnurajeevan.libroabs.libro.*
import io.github.kevincianfarini.cardiologist.intervalPulse
import io.ktor.client.HttpClient
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

fun main(args: Array<String>) {
  NoOpCliktCommand(name = "librofm-abs")
    .subcommands(Run())
    .main(args)
}

class Run : CliktCommand("run") {
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
    .enum<BookFormat>()
    .default(BookFormat.MP3)

  private val verbose by option("--verbose", "-v", envvar = "VERBOSE")
    .flag(default = false)

  // Limits the number of books pulled down to 1
  private val devMode by option("--dev-mode", "-d", envvar = "DEV_MODE")
    .flag(default = false)

  private val libroFmUsername by option("--libro-fm-username", envvar = "LIBRO_FM_USERNAME")
    .required()

  private val libroFmPassword by option("--libro-fm-password", envvar = "LIBRO_FM_PASSWORD")
    .required()

  private val lfdLogger: (String) -> Unit = {
    if (verbose) {
      println(it)
    }
  }

  private val libroClient by lazy {
    LibroApiHandler(
      client = HttpClient { },
      dataDir = dataDir,
      dryRun = dryRun,
      verbose = verbose,
      lfdLogger = lfdLogger
    )
  }

  private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  override fun run() {
    println(
      """
        Starting up!
        internal port: $port
        syncInterval: $syncInterval
        dryRun: $dryRun
        renameChapters: $renameChapters
        writeTitleTag: $writeTitleTag
        format: $format
        verbose: $verbose
        devMode: $devMode
        libroFmUsername: $libroFmUsername
        libroFmPassword: ${libroFmPassword.map { "*" }.joinToString("")}
      """.trimIndent()
    )

    runBlocking {
      val dataDir = File(dataDir).apply {
        if (!exists()) {
          mkdirs()
        }
      }
      val tokenFile = File("$dataDir/token.txt")
      if (!tokenFile.exists()) {
        lfdLogger("Token file not found, logging in")
        libroClient.fetchLoginData(libroFmUsername, libroFmPassword)
      }

      libroClient.fetchLibrary()
      processLibrary()

      launch {
        lfdLogger("Sync Interval: $syncInterval")
        val syncIntervalTimeUnit = when (syncInterval) {
          "h" -> 1.hours
          "d" -> 1.days
          "w" -> 7.days
          else -> error("Unhandled sync interval")
        }

        Clock.System.intervalPulse(syncIntervalTimeUnit)
          .beat { _, _ ->
            lfdLogger("Checking library on pulse!")
            libroClient.fetchLibrary()
            processLibrary()
          }
      }

      serverScope.launch {
        embeddedServer(
          factory = Netty,
          port = port,
          host = "0.0.0.0",
          module = {
            routing {
              post("/update") {
                call.respondText("Updating!")
                libroClient.fetchLibrary()
                processLibrary()
              }
            }
          }
        ).start(wait = true)
      }
    }
  }

  private fun getLibrary(): LibraryMetadata {
    return Json.decodeFromString<LibraryMetadata>(
      File("$dataDir/library.json").readText()
    )
  }

  private suspend fun processLibrary() {
    val localLibrary = getLibrary()

    localLibrary.audiobooks
      .let {
        if (devMode) {
          it.take(1)
        } else {
          it
        }
      }
      .forEach { book ->
        val targetDir = targetDir(book)

        if (!targetDir.exists()) {
          lfdLogger("downloading ${book.title}")
          targetDir.mkdirs()
          when (format) {
            BookFormat.MP3 -> {
              val downloadData = downloadBookAsMp3s(book, targetDir)

              if (renameChapters) {
                libroClient.renameChapters(
                  title = book.title,
                  tracks = downloadData.tracks,
                  targetDirectory = targetDir,
                  writeTitleTag = writeTitleTag
                )
              }
            }
            BookFormat.M4B -> {
              downloadBookAsM4b(
                book = book,
                targetDir = targetDir
              )
            }
          }
        } else {
          lfdLogger("skipping ${book.title} as it exists on the filesystem!")
        }
      }
  }

  private suspend fun downloadBookAsMp3s(
    book: Book,
    targetDir: File
  ): Mp3DownloadMetadata {
    val downloadData = libroClient.fetchMp3DownloadMetadata(book.isbn)
    libroClient.downloadMp3s(
      data = downloadData.parts,
      targetDirectory = targetDir
    )
    return downloadData
  }

  private suspend fun downloadBookAsM4b(
    book: Book,
    targetDir: File
  ) {
    val m4bMetadata = libroClient.fetchM4bMetadata(book.isbn)
    libroClient.downloadM4b(m4bMetadata.m4b_url, targetDir)
  }

  private fun targetDir(book: Book): File {
    val targetDir = File("$mediaDir/${book.authors.first()}/${book.title}")
    return targetDir
  }
}

