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
import io.ktor.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import net.bramp.ffmpeg.FFmpeg
import net.bramp.ffmpeg.FFmpegExecutor
import net.bramp.ffmpeg.FFprobe
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

  private val seriesDirectory by option("--series-directory", envvar="SERIES_DIRECTORY")
    .flag(default = false)

  private val ffmpegPath by option("--ffmpeg-path")
    .default("/usr/bin/ffmpeg")

  private val ffprobePath by option("--ffprobe-path")
    .default("/usr/bin/ffprobe")

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

  private val libroFmApi by lazy {
    LibroApiHandler(
      client = HttpClient { },
      dataDir = dataDir,
      dryRun = dryRun,
      verbose = verbose,
      lfdLogger = lfdLogger
    )
  }

  private val m4bUtil by lazy {
    M4BUtil(
      ffprobePath = ffprobePath,
      executor = FFmpegExecutor(FFmpeg(ffmpegPath), FFprobe(ffprobePath))
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
        ffmpegPath: $ffmpegPath
        ffprobePath: $ffprobePath
        verbose: $verbose
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
        libroFmApi.fetchLoginData(libroFmUsername, libroFmPassword)
      }

      libroFmApi.fetchLibrary()
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
            libroFmApi.fetchLibrary()
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
                libroFmApi.fetchLibrary()
                processLibrary()
              }
              post("/convertToM4b/{isbn}") {
                call.parameters["isbn"]?.let { isbn ->
                  val overwrite = !call.queryParameters["overwrite"].isNullOrBlank()
                  if (isbn == "all") {
                    call.respondText("Starting conversion process for all books in the library" + if (overwrite) " and overwriting existing books" else "")
                    convertAllBooksToM4b(overwrite)
                  } else {
                    call.respondText("Starting conversion process for $isbn" + if (overwrite) " and overwriting existing book" else "")
                    convertBookToM4b(isbn, overwrite)
                  }
                }
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
          val downloadData = downloadBook(book, targetDir)

          if (renameChapters) {
            libroFmApi.renameChapters(
              title = book.title,
              tracks = downloadData.tracks,
              targetDirectory = targetDir,
              writeTitleTag = writeTitleTag
            )
          }
          if (format == BookFormat.M4B || format == BookFormat.Both) {
            lfdLogger("Converting ${book.title} from mp3 to m4b.")
            convertBookToM4b(book)
          }
        } else {
          lfdLogger("skipping ${book.title} as it exists on the filesystem!")
        }
      }
  }

  private suspend fun downloadBook(
    book: Book,
    targetDir: File
  ): DownloadMetadata {
    val downloadData = libroFmApi.fetchDownloadMetadata(book.isbn)
    libroFmApi.fetchAudioBook(
      data = downloadData.parts,
      targetDirectory = targetDir
    )
    return downloadData
  }

  private suspend fun convertAllBooksToM4b(overwrite: Boolean = false) {
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
        convertBookToM4b(book, overwrite)
      }
  }

  private suspend fun convertBookToM4b(isbn: String, overwrite: Boolean = false) {
    val localLibrary = getLibrary()
    val book = localLibrary.audiobooks.find { it.isbn == isbn }
    if (book == null) {
      lfdLogger("Book with isbn $isbn not found!")
    } else {
      convertBookToM4b(book, overwrite)
    }
  }

  private suspend fun convertBookToM4b(book: Book, overwrite: Boolean = false) {
    val targetDir = targetDir(book)
    var downloadMetaData: DownloadMetadata? = null

    // Check that book is downloaded and Mp3s are present
    if (!targetDir.exists()) {
      lfdLogger("Book ${book.title} is not downloaded yet!")
      targetDir.mkdirs()
      downloadMetaData = downloadBook(book, targetDir)
    }

    if (!overwrite) {
      val targetFile = targetDir.combineSafe("${book.title}.m4b")
      if (targetFile.exists()) {
        lfdLogger("Skipping ${book.title} as it's already converted!")
        return
      }
    }

    val chapterFiles =
      targetDir.listFiles { file -> file.extension == "mp3" }
    if (chapterFiles == null || chapterFiles.isEmpty()) {
      lfdLogger("Book ${book.title} does not have mp3 files downloaded. Downloading the book again.")
      downloadMetaData = downloadBook(book, targetDir)
    }

    if (downloadMetaData == null) {
      downloadMetaData = libroFmApi.fetchDownloadMetadata(book.isbn)
    }

    lfdLogger("Converting ${book.title} from mp3 to m4b.")
    m4bUtil.convertBookToM4b(book, downloadMetaData.tracks, targetDir)

    if (format == BookFormat.M4B) {
      lfdLogger("Deleting obsolete mp3 files for ${book.title}")
      libroFmApi.deleteMp3Files(targetDir)
    }
  }

  private fun targetDir(book: Book): File {
    if (book.series != null && seriesDirectory) {
      val targetDir = File("$mediaDir/${book.authors.first()}/${book.series}/${book.title}")
    }
    else {
      val targetDir = File("$mediaDir/${book.authors.first()}/${book.title}")
    }

    return targetDir
  }
}

