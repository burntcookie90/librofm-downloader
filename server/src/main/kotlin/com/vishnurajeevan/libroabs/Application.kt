package com.vishnurajeevan.libroabs

import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import net.bramp.ffmpeg.FFmpeg
import net.bramp.ffmpeg.FFmpegExecutor
import net.bramp.ffmpeg.FFprobe
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

suspend fun main(args: Array<String>) = LibroDownloader().main(args)

enum class ApplicationLogLevel {
  NONE, INFO, VERBOSE
}

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

  private val lfdLogger: (String) -> Unit = {
    when (logLevel) {
      ApplicationLogLevel.INFO, ApplicationLogLevel.VERBOSE -> println(it)
      else -> {}
    }
  }

  private val libroClient by lazy {
    LibroApiHandler(
      client = HttpClient { },
      dataDir = dataDir,
      dryRun = dryRun,
      logLevel = logLevel,
      lfdLogger = lfdLogger
    )
  }

  private val ffmpegClient by lazy {
    FfmpegClient(
      ffprobePath = ffprobePath,
      executor = FFmpegExecutor(
        FFmpeg(ffmpegPath),
        FFprobe(ffprobePath)
      )
    )
  }

  private val appScope = CoroutineScope(Dispatchers.Default)
  private val processingScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
  private val processingSemaphore by lazy { Semaphore(parallelCount) }

  override suspend fun run() {
    println(
      """
        Starting up!
        internal port: $port
        syncInterval: $syncInterval
        parallelCount: $parallelCount
        dryRun: $dryRun
        renameChapters: $renameChapters
        writeTitleTag: $writeTitleTag
        format: $format
        logLevel: $logLevel
        limit: $limit
        libroFmUsername: $libroFmUsername
        libroFmPassword: ${libroFmPassword.map { "*" }.joinToString("")}
      """.trimIndent()
    )

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

    appScope.launch {
      libroClient.fetchLibrary()
      processLibrary()
    }

    appScope.launch {
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

  private suspend fun getLibrary(): LibraryMetadata = withContext(Dispatchers.IO) {
    return@withContext Json.decodeFromString<LibraryMetadata>(
      File("$dataDir/library.json").readText()
    )
  }

  private suspend fun processLibrary() {
    val localLibrary = getLibrary()

    localLibrary.audiobooks
      .let {
        if (limit == -1) {
          it
        } else {
          it.take(limit)
        }
      }
      .forEach { book ->
        processingScope.launch {
          processingSemaphore.withPermit {
            val targetDir = targetDir(book)

            if (!targetDir.exists()) {
              lfdLogger("Downloading ${book.title}")
              targetDir.mkdirs()
              when (format) {
                BookFormat.MP3 -> {
                  downloadMp3sAndRename(book, targetDir)
                }

                BookFormat.M4B_MP3_FALLBACK -> {
                  downloadBookAsM4b(
                    book = book,
                    targetDir = targetDir
                  ).onFailure {
                    lfdLogger("M4B download for ${book.title} failed, falling back to MP3")
                    downloadMp3sAndRename(book, targetDir)
                  }
                }

                BookFormat.M4B_CONVERT_FALLBACK -> {
                  downloadBookAsM4b(
                    book = book,
                    targetDir = targetDir
                  ).onFailure {
                    lfdLogger("M4B download for ${book.title} failed, falling back to conversion")
                    downloadMp3sAndRename(book, targetDir)
                    convertBookToM4b(book)
                  }
                }
              }
            } else {
              lfdLogger("skipping ${book.title} as it exists on the filesystem!")
            }
          }
        }
      }
  }

  private suspend fun downloadMp3sAndRename(book: Book, targetDir: File) {
    val downloadData = downloadBookAsMp3s(book, targetDir)

    if (renameChapters) {
      renameChapters(
        title = book.title,
        tracks = downloadData.tracks,
        targetDirectory = targetDir,
        writeTitleTag = writeTitleTag
      )
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
  ): Result<Unit> {
    val m4bMetadata = libroClient.fetchM4bMetadata(book.isbn)
    if (m4bMetadata.isSuccess) {
      libroClient.downloadM4b(m4bMetadata.getOrThrow().m4b_url, targetDir)
      return Result.success(Unit)
    } else {
      return Result.failure(Exception("M4B Not Found"))
    }
  }

  private suspend fun convertBookToM4b(book: Book) {
    val targetDir = targetDir(book)
    var downloadMetaData: Mp3DownloadMetadata? = null

    // Check that book is downloaded and Mp3s are present
    if (!targetDir.exists()
      && targetDir.listFiles { it.extension == "mp3" }.isEmpty()
    ) {
      lfdLogger("Book ${book.title} is not downloaded yet!")
      targetDir.mkdirs()
      downloadMetaData = downloadBookAsMp3s(book, targetDir)
    }

    val chapterFiles =
      targetDir.listFiles { file -> file.extension == "mp3" }
    if (chapterFiles == null || chapterFiles.isEmpty()) {
      lfdLogger("Book ${book.title} does not have mp3 files downloaded. Downloading the book again.")
      downloadMetaData = downloadBookAsMp3s(book, targetDir)
    }

    if (downloadMetaData == null) {
      downloadMetaData = libroClient.fetchMp3DownloadMetadata(book.isbn)
    }

    lfdLogger("Converting ${book.title} from mp3 to m4b.")
    ffmpegClient.convertBookToM4b(book, downloadMetaData.tracks, targetDir, audioQuality)

    lfdLogger("Deleting obsolete mp3 files for ${book.title}")
    deleteMp3Files(targetDir)
  }

  private fun targetDir(book: Book): File {
    val targetDir = File("$mediaDir/${book.authors.first()}/${book.title}")
    return targetDir
  }

  private suspend fun deleteMp3Files(targetDirectory: File) = withContext(Dispatchers.IO) {
    targetDirectory.listFiles { file -> file.extension == "mp3" }
      ?.forEach { it.delete() }
  }

  private suspend fun renameChapters(
    title: String,
    tracks: List<Tracks>,
    targetDirectory: File,
    writeTitleTag: Boolean
  ) = withContext(Dispatchers.IO) {
    if (tracks.any { it.chapter_title == null }) return@withContext

    val sortedTracks = tracks.sortedBy { it.number }

    val newFilenames = createFilenames(sortedTracks, title)

    val trackTitles = createTrackTitles(sortedTracks)

    targetDirectory.listFiles()
      ?.sortedBy { it.nameWithoutExtension }
      ?.forEachIndexed({ index, file ->
        val newFilename = newFilenames[index]
        val newFile = File(targetDirectory, "$newFilename.${file.extension}")
        file.renameTo(newFile)

        if (writeTitleTag) {
          val audioFile = AudioFileIO.read(newFile)
          val tag = audioFile.tag
          tag.setField(FieldKey.TITLE, trackTitles[index])
          audioFile.commit()
        }
      })
  }
}

