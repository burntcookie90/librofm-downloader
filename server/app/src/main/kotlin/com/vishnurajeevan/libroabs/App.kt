package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.converter.ffmpeg.FfmpegClient
import com.vishnurajeevan.libroabs.healthcheck.HealthcheckApi
import com.vishnurajeevan.libroabs.libro.LibroApiHandler
import com.vishnurajeevan.libroabs.libro.createFilenames
import com.vishnurajeevan.libroabs.libro.createTrackTitles
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.App
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.models.libro.Tracks
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.Format
import com.vishnurajeevan.libroabs.models.server.LibroDownloadHistory
import com.vishnurajeevan.libroabs.models.server.LibroDownloadItem
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.createPath
import com.vishnurajeevan.libroabs.server.setupServer
import com.vishnurajeevan.libroabs.storage.Storage
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import io.github.kevincianfarini.cardiologist.fixedPeriodPulse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.tatarka.inject.annotations.Inject
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File
import kotlin.collections.isEmpty
import kotlin.collections.plus
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@Inject
@SingleIn(AppScope::class)
class App(
  private val serverInfo: ServerInfo,
  private val healthCheckClient: HealthcheckApi,
  @Named("healthcheck-id") private val hcToken: String?,
  private val ffmpegClient: FfmpegClient,
  private val libroClient: LibroApiHandler,
  private val trackerConnector: TrackerConnector?,
  @App private val appScope: CoroutineScope,
  @Io private val processingScope: CoroutineScope,
  private val processingSemaphore: Semaphore,
  private val lfdLogger: Logger,
  private val downloadHistory: Storage<LibroDownloadHistory>,
) {

  suspend fun run() {
    healthCheckClient.pingWithToken()
    libroClient.fetchLoginData(serverInfo.libroUserName, serverInfo.libroPassword)
    trackerConnector?.login()

    appScope.launch {
      libroClient.fetchLibrary()
      processLibrary()
    }

    appScope.launch {
      lfdLogger.log("Sync Interval: ${serverInfo.syncInterval}")
      val syncIntervalTimeUnit = when (serverInfo.syncInterval) {
        "h" -> 1.hours
        "d" -> 1.days
        "w" -> 7.days
        else -> error("Unhandled sync interval")
      }

      Clock.System.fixedPeriodPulse(syncIntervalTimeUnit)
        .beat { _, _ ->
          lfdLogger.log("Checking library on pulse!")
          fullUpdate()
        }
    }

    appScope.launch {
      trackerConnector?.syncWishlistFromConnector()
      trackerConnector?.syncWishlistToConnector()
    }

    setupServer(
      onUpdate = { fullUpdate(it) },
      serverInfo = serverInfo
    ).start(wait = true)
  }

  private suspend fun fullUpdate(overwrite: Boolean = false) {
    healthCheckClient.pingWithToken()
    libroClient.fetchLibrary()
    processLibrary(overwrite)
    trackerConnector?.syncWishlistFromConnector()
    trackerConnector?.syncWishlistToConnector()

  }

  private suspend fun TrackerConnector.syncWishlistFromConnector() {
    lfdLogger.log("Syncing Wishlist from Tracker")
    libroClient.syncWishlist(
      getWantedBooks()
        .flatMap { books -> books.connectorAudioBook.map { it.isbn13 } }
        .filterNotNull()
    )
  }

  private suspend fun TrackerConnector.syncWishlistToConnector() {
    lfdLogger.log("Syncing Wishlist to Tracker")
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

  private suspend fun processLibrary(overwrite: Boolean = false) {
    val localLibrary = libroClient.getLocalLibrary()

    // We need to prefill the download history with the old filesystem based history
    if (downloadHistory.getData().books.isEmpty() && File(serverInfo.mediaDir).listFiles().isNotEmpty()) {
      lfdLogger.log("Migrating to file based history")
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
        if (serverInfo.limit == -1) {
          it
        } else {
          it.take(serverInfo.limit)
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
            lfdLogger.log("Downloading ${book.title}")
            when (serverInfo.format) {
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
                    lfdLogger.log("M4B download for ${book.title} failed, falling back to MP3")
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
                    lfdLogger.log("M4B download for ${book.title} failed, falling back to conversion")
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
      lfdLogger.log("Writing $downloadResult to history")
      downloadHistory.update {
        it.copy(
          books = it.books + downloadResult
        )
      }
    }

    syncOwned(localLibrary)
  }

  private suspend fun syncOwned(localLibrary: LibraryMetadata) {
    lfdLogger.log("Syncing Owned to Tracker")
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
          .any { it in serverInfo.skipTrackingIsbns }
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

    if (serverInfo.renameChapters) {
      renameChapters(
        title = book.title,
        tracks = downloadData.tracks,
        targetDirectory = targetDir,
        writeTitleTag = serverInfo.writeTitleTag
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
      lfdLogger.log("Book ${book.title} is not downloaded yet!")
      targetDir.mkdirs()
      downloadMetaData = downloadBookAsMp3s(book, targetDir)
    }

    val chapterFiles =
      targetDir.listFiles { file -> file.extension == "mp3" }
    if (chapterFiles == null || chapterFiles.isEmpty()) {
      lfdLogger.log("Book ${book.title} does not have mp3 files downloaded. Downloading the book again.")
      downloadMetaData = downloadBookAsMp3s(book, targetDir)
    }

    if (downloadMetaData == null) {
      downloadMetaData = libroClient.fetchMp3DownloadMetadata(book.isbn)
    }

    lfdLogger.log("Converting ${book.title} from mp3 to m4b.")

    if (!serverInfo.dryRun) {
      ffmpegClient.convertBookToM4b(
        book = book,
        tracks = downloadMetaData.tracks,
        targetDirectory = targetDir,
        audioQuality = serverInfo.audioQuality
      )

      lfdLogger.log("Deleting obsolete mp3 files for ${book.title}")

      deleteMp3Files(targetDir)
    }
  }

  private fun targetDir(book: Book) = File("${serverInfo.mediaDir}/${book.createPath(serverInfo.pathPattern)}")
    .also {
      lfdLogger.log("Target Directory: $it")
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

  private suspend fun HealthcheckApi.pingWithToken() {
    hcToken?.let { if (it.isNotEmpty()) ping(it) }
  }
}