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
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.connector.hardcover.HardcoverTrackerConnector
import com.vishnurajeevan.libroabs.ffmpeg.FfmpegClient
import com.vishnurajeevan.libroabs.healthchck.HealthcheckApi
import com.vishnurajeevan.libroabs.healthchck.createHealthcheckApi
import com.vishnurajeevan.libroabs.libro.LibroApiHandler
import com.vishnurajeevan.libroabs.libro.createFilenames
import com.vishnurajeevan.libroabs.libro.createTrackTitles
import com.vishnurajeevan.libroabs.libro.models.Book
import com.vishnurajeevan.libroabs.libro.models.LibraryMetadata
import com.vishnurajeevan.libroabs.libro.models.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.libro.models.Tracks
import com.vishnurajeevan.libroabs.models.BookFormat
import com.vishnurajeevan.libroabs.models.Format
import com.vishnurajeevan.libroabs.models.LibroDownloadHistory
import com.vishnurajeevan.libroabs.models.LibroDownloadItem
import com.vishnurajeevan.libroabs.models.ServerInfo
import com.vishnurajeevan.libroabs.models.createPath
import com.vishnurajeevan.libroabs.options.HardcoverOptionGroup
import com.vishnurajeevan.libroabs.options.HealthchecksIoOptionGroup
import com.vishnurajeevan.libroabs.route.Info
import com.vishnurajeevan.libroabs.route.Update
import com.vishnurajeevan.libroabs.storage.RealStorage
import com.vishnurajeevan.libroabs.storage.Storage
import de.jensklingenberg.ktorfit.Ktorfit
import io.github.kevincianfarini.cardiologist.fixedPeriodPulse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.onClick
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.unsafe
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
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

  private val lfdLogger: (String) -> Unit = {
    when (logLevel) {
      ApplicationLogLevel.INFO, ApplicationLogLevel.VERBOSE -> println(it)
      else -> {}
    }
  }

  private val defaultHttpClient by lazy {
    HttpClient {
      install(Logging) {
        logger = object : Logger {
          override fun log(message: String) {
            lfdLogger(message)
          }

        }
        level = when (logLevel) {
          ApplicationLogLevel.NONE -> LogLevel.NONE
          ApplicationLogLevel.INFO -> LogLevel.INFO
          ApplicationLogLevel.VERBOSE -> LogLevel.ALL
        }
      }
    }
  }

  private val libroClient by lazy {
    LibroApiHandler(
      client = defaultHttpClient,
      dataDir = dataDir,
      dryRun = dryRun,
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

  private val healthCheckClient: HealthcheckApi? by lazy {
    if (!healthChecksIoOptions?.healthCheckId.isNullOrEmpty()) {
      healthChecksIoOptions?.let {
        Ktorfit.Builder()
          .baseUrl(if (it.healthCheckHost.endsWith("/")) it.healthCheckHost else "${it.healthCheckHost}/")
          .httpClient(defaultHttpClient)
          .build()
          .createHealthcheckApi()
      }
    } else {
      null
    }
  }

  private val trackerConnector: TrackerConnector? by lazy {
    when {
      !hardcoverOptions?.hardcoverToken.isNullOrEmpty() -> hardcoverOptions?.let {
        HardcoverTrackerConnector(
          token = it.hardcoverToken,
          endpoint = it.hardcoverEndpoint,
          logger = lfdLogger,
          dispatcher = Dispatchers.IO
        )
      }

      else -> {
        null
      }
    }
  }

  private val downloadHistory: Storage<LibroDownloadHistory> by lazy {
    RealStorage.Factory<LibroDownloadHistory>()
      .create(
        file = File("$dataDir/download_history.json"),
        initial = LibroDownloadHistory(),
        serializer = serializer(),
        dispatcher = Dispatchers.IO,
        logger = lfdLogger
      )
  }

  private val appScope = CoroutineScope(Dispatchers.Default)
  private val processingScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
  private val processingSemaphore by lazy { Semaphore(parallelCount) }

  override suspend fun run() {
    val serverRuntimeInfo = listOf(
      "port: $port",
      "syncInterval: $syncInterval",
      "parallelCount: $parallelCount",
      "dryRun: $dryRun",
      "renameChapters: $renameChapters",
      "writeTitleTag: $writeTitleTag",
      "format: $format",
      "logLevel: $logLevel",
      "limit: $limit",
      "pathPattern: $pathPattern",
      "healthCheckHost: ${healthChecksIoOptions?.healthCheckHost}",
      "healthCheckId: ${healthChecksIoOptions?.healthCheckId}",
      "libroFmUsername: $libroFmUsername",
      "libroFmPassword: ${libroFmPassword.map { "*" }.joinToString("")}",
    )
    println(serverRuntimeInfo.joinToString("\n"))


    healthCheckClient?.ping()
    libroClient.fetchLoginData(libroFmUsername, libroFmPassword)
    trackerConnector?.login()

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

      Clock.System.fixedPeriodPulse(syncIntervalTimeUnit)
        .beat { _, _ ->
          lfdLogger("Checking library on pulse!")
          fullUpdate()
        }
    }

    appScope.launch {
      trackerConnector?.syncWishlistFromConnector()
      trackerConnector?.syncWishlistToConnector()
    }

    setupServer(serverRuntimeInfo).start(wait = true)
  }

  private suspend fun fullUpdate(overwrite: Boolean = false) {
    healthCheckClient?.ping()
    libroClient.fetchLibrary()
    processLibrary(overwrite)
    trackerConnector?.syncWishlistFromConnector()
    trackerConnector?.syncWishlistToConnector()

  }

  private suspend fun TrackerConnector.syncWishlistFromConnector() {
    lfdLogger("Syncing Wishlist from Tracker")
    libroClient.syncWishlist(
      getWantedBooks()
        .flatMap { books -> books.connectorAudioBook.map { it.isbn13 } }
        .filterNotNull()
    )
  }

  private suspend fun TrackerConnector.syncWishlistToConnector() {
    lfdLogger("Syncing Wishlist to Tracker")
    val existingWantedBooks = getWantedBooks().map { it.connectorAudioBook.map { it.isbn13 } }.flatten().filterNotNull()
    val libroWishlist = libroClient.fetchWishlist()
    val isbnsToSync = libroWishlist.audiobooks.filter {
      it.isbn !in existingWantedBooks
    }.map { it.isbn }

    val editions = getEditions(isbnsToSync)
    editions.forEach {
      markWanted(it)
    }

    val editionsNotFound = isbnsToSync.minus(editions.map { it.connectorAudioBook.mapNotNull { it.isbn13 } }.flatten())
    libroWishlist.audiobooks
      .filter { it.isbn in editionsNotFound }
      .map { libroClient.fetchBookDetails(it.isbn) }
      .map { it to trackerConnector?.searchByTitle(it.title, it.authors.first()) }
      .mapNotNull { (audiobook, trackerBook) ->
        trackerBook?.let {
          trackerConnector?.createEdition(
            it.copy(
              releaseDate = audiobook.publication_date.toLocalDateTime(TimeZone.UTC).date,
              connectorAudioBook = listOf(
                ConnectorAudioBookEdition(
                  id = "",
                  isbn13 = audiobook.isbn
                )
              )
            )
          )
        }
      }
      .forEach { trackerConnector?.markWanted(it) }
  }

  private fun setupServer(serverRuntimeInfo: List<String>)
    : EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
    val serverInfo = ServerInfo(
      port = port,
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
      healthCheckId = healthChecksIoOptions?.healthCheckHost.orEmpty(),
    )
    return embeddedServer(
      factory = Netty,
      port = port,
      host = "0.0.0.0",
      module = {
        install(Resources)
        install(ContentNegotiation) {
          json(Json { })
        }
        routing {
          get<Info> {
            call.respond(
              serverInfo
            )
          }
          get<Update> {
            val overwrite = it.overwrite ?: false
            call.respondText("Updating, overwrite: $overwrite!")
            fullUpdate(overwrite)
          }
          head("/") {
            call.response.headers.append(
              "App-Hash",
              "${serverInfo.hashCode()}"
            )
            call.respond("")
          }
          get("/") {
            call.respondHtml(HttpStatusCode.OK) {
              head {
                title {
                  +"libro.fm Downloader"
                }
                script {
                  unsafe {
                    +"""
                                          function callUpdateFunction() {
                                              const overwriteChecked = document.getElementById('overwriteCheckbox').checked;
                                              fetch('/update?overwrite=' + overwriteChecked, {
                                                  method: 'GET'
                                              });
                                          }
                                      """.trimIndent()
                  }
                }
              }
              body {
                serverRuntimeInfo.forEach {
                  p {
                    +it
                  }
                }
                button {
                  id = "updateButton"
                  onClick = "callUpdateFunction()"
                  +"Update Library"
                }
                +" "
                input(type = InputType.checkBox) {
                  id = "overwriteCheckbox"
                }
                +" overwrite?"
              }
            }
          }
        }
      }
    )
  }

  private suspend fun processLibrary(overwrite: Boolean = false) {
    val localLibrary = libroClient.getLocalLibrary()

    // We need to prefill the download history with the old filesystem based history
    if (downloadHistory.getData().books.isEmpty() && File(mediaDir).listFiles().isNotEmpty()) {
      lfdLogger("Migrating to file based history")
      localLibrary.audiobooks
        .forEach { book ->
          val targetDir = targetDir(book = book)
          if (targetDir.exists() && targetDir.listFiles().isNotEmpty()) {
            val isMp3 = targetDir.listFiles().map { it.extension }.any { it == "mp3" }
            downloadHistory.update {
              val libroDownloadItem = LibroDownloadItem(
                isbn = book.isbn,
                format = if (isMp3) Format.MP3 else Format.M4B,
                path = targetDir.path
              )
              it.copy(
                books = it.books + (book.isbn to libroDownloadItem)
              )
            }
          }
        }
    }

    val downloadResult = localLibrary.audiobooks
      .let {
        if (limit == -1) {
          it
        } else {
          it.take(limit)
        }
      }
      .filter {
        if (!overwrite) {
          !downloadHistory.getData().books.containsKey(it.isbn)
        } else {
          true
        }
      }
      .map { book ->
        processingScope.async {
          processingSemaphore.withPermit {
            val targetDir = targetDir(book).also { it.mkdirs() }
            lfdLogger("Downloading ${book.title}")
            when (format) {
              BookFormat.MP3 -> {
                downloadMp3sAndRename(book, targetDir)
                LibroDownloadItem(
                  isbn = book.isbn,
                  format = Format.MP3,
                  path = targetDir.path,
                )
              }

              BookFormat.M4B_MP3_FALLBACK -> {
                val result = downloadBookAsM4b(
                  book = book,
                  targetDir = targetDir
                )

                when {
                  result.isSuccess -> {
                    LibroDownloadItem(
                      isbn = book.isbn,
                      format = Format.M4B,
                      path = targetDir.path,
                    )
                  }

                  else -> {
                    lfdLogger("M4B download for ${book.title} failed, falling back to MP3")
                    downloadMp3sAndRename(book, targetDir)
                    LibroDownloadItem(
                      isbn = book.isbn,
                      format = Format.MP3,
                      path = targetDir.path,
                    )
                  }
                }
              }

              BookFormat.M4B_CONVERT_FALLBACK -> {
                val result = downloadBookAsM4b(
                  book = book,
                  targetDir = targetDir
                )
                when {
                  result.isSuccess -> {
                    LibroDownloadItem(
                      isbn = book.isbn,
                      format = Format.M4B,
                      path = targetDir.path,
                    )
                  }

                  else -> {
                    lfdLogger("M4B download for ${book.title} failed, falling back to conversion")
                    downloadMp3sAndRename(book, targetDir)
                    convertBookToM4b(book)
                    LibroDownloadItem(
                      isbn = book.isbn,
                      format = Format.M4B_CONVERTED,
                      path = targetDir.path,
                    )
                  }
                }
              }
            }
          }
        }
      }
      .awaitAll()
      .associateBy { it.isbn }

    if (!downloadResult.isEmpty()) {
      lfdLogger("Writing $downloadResult to history")
      downloadHistory.update {
        it.copy(
          books = it.books + downloadResult
        )
      }
    }

    syncOwned(localLibrary)
  }

  private suspend fun syncOwned(localLibrary: LibraryMetadata) {
    lfdLogger("Syncing Owned to Tracker")
    val isbn13s = localLibrary.audiobooks.map { it.isbn }
    val editions: List<ConnectorBook> = trackerConnector?.getEditions(isbn13s).orEmpty()
    val editionsNotFound = isbn13s.minus(editions.map { it.connectorAudioBook.mapNotNull { it.isbn13 } }.flatten())
    val ownedBooks: List<ConnectorBook> = trackerConnector?.getOwnedBooks().orEmpty()

    val ownedIsbns = ownedBooks.map { books ->
      books.connectorAudioBook.mapNotNull { it.isbn13 }
    }.flatten()

    editions
      .filterNot {
        it.connectorAudioBook
          .mapNotNull { it.isbn13 }
          .any { it in ownedIsbns }
      }
      .filterNot { book ->
        book.connectorAudioBook
          .mapNotNull { it.isbn13 }
          .any { it in skipTrackingIsbns }
      }
      .forEach {
        trackerConnector?.markOwned(it)
      }
    localLibrary.audiobooks
      .filter { it.isbn in editionsNotFound }
      .map { it to trackerConnector?.searchByTitle(it.title, it.authors.first()) }
      .mapNotNull { (audiobook, trackerBook) ->
        trackerBook?.let {
          trackerConnector?.createEdition(
            it.copy(
              releaseDate = audiobook.publication_date.toLocalDateTime(TimeZone.UTC).date,
              connectorAudioBook = listOf(
                ConnectorAudioBookEdition(
                  id = "",
                  isbn13 = audiobook.isbn
                )
              )
            )
          )
        }
      }
      .forEach { trackerConnector?.markOwned(it) }
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

    if (!dryRun) {
      ffmpegClient.convertBookToM4b(
        book = book,
        tracks = downloadMetaData.tracks,
        targetDirectory = targetDir,
        audioQuality = audioQuality
      )

      lfdLogger("Deleting obsolete mp3 files for ${book.title}")

      deleteMp3Files(targetDir)
    }
  }

  private fun targetDir(book: Book) = File("${mediaDir}/${book.createPath(pathPattern)}").also {
    lfdLogger("Target Directory: $it")
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

  private suspend fun HealthcheckApi.ping() {
    healthChecksIoOptions?.let {
      ping(it.healthCheckId)
    }
  }
}

