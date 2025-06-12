package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.converter.ffmpeg.FfmpegClient
import com.vishnurajeevan.libroabs.db.repo.DownloadHistoryRepo
import com.vishnurajeevan.libroabs.db.repo.TrackerWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.DownloadItem
import com.vishnurajeevan.libroabs.db.writer.TrackerWishlistSyncStatus
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
import com.vishnurajeevan.libroabs.models.libro.WishlistItemSyncStatus
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.DownloadedFormat
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.server.setupServer
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import com.vishnurajeevan.libroabs.storage.models.LibroDownloadItem
import io.github.kevincianfarini.cardiologist.fixedPeriodPulse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

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
  @Io private val ioDispatcher: CoroutineDispatcher,
  private val processingSemaphore: Semaphore,
  private val lfdLogger: Logger,
  private val downloadHistoryRepo: DownloadHistoryRepo,
  private val dbWriter: DbWriter,
  private val targetDir: (Book) -> File,
  private val trackerWishlistSyncStatusRepo: TrackerWishlistSyncStatusRepo,
) {

  suspend fun run() {
    libroClient.fetchLoginData(serverInfo.libroUserName, serverInfo.libroPassword)
    trackerConnector?.login()

    appScope.launch {
      fullUpdate(delayForInitial = !serverInfo.dryRun)
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

    setupServer(
      onUpdate = { fullUpdate(it) },
      serverInfo = serverInfo
    ).start(wait = true)
  }

  private suspend fun fullUpdate(
    delayForInitial: Boolean = false,
    overwrite: Boolean = false
  ) {
    val delay = if (delayForInitial || overwrite) 1.minutes else 0.minutes

    healthCheckClient.startMeasureWithToken()
    libroClient.fetchLibrary()
    delay(delay)
    processLibrary(overwrite)
    delay(delay)
    trackerConnector?.syncWishlistFromConnector()
    delay(delay)
    trackerConnector?.syncWishlistToConnector()
    healthCheckClient.pingWithToken()
  }

  private suspend fun TrackerConnector.syncWishlistFromConnector() {
    lfdLogger.log("Syncing Wishlist from Tracker")
    libroClient.syncWishlist(
      getWantedBooks()
        .flatMap { books -> books.connectorAudioBook.map { it.isbn13 } }
        .filterNotNull()
    )
  }

  private fun List<ConnectorBook>.mapIsbns() = map { it.connectorAudioBook.map { it.isbn13 } }
    .flatten()
    .filterNotNull()

  private suspend fun TrackerConnector.syncWishlistToConnector() {
    lfdLogger.log("Syncing Wishlist to Tracker")
    val existingWantedBooks = getWantedBooks().mapIsbns()
    val ownedBooks = getOwnedBooks().mapIsbns()
    val readBooks = getReadBooks().mapIsbns()
    val previouslySynced = trackerWishlistSyncStatusRepo.getSyncedIsbns()
    val isbnsToSkip = existingWantedBooks + ownedBooks + readBooks + previouslySynced
    val libroWishlist = libroClient.fetchWishlist()
    val isbnsToSync = libroWishlist.audiobooks
      .map { it.isbn }
      .filter { it !in isbnsToSkip }

    val editions = getEditions(isbnsToSync)
    editions
      .filter { edition ->
        edition.connectorAudioBook
          .none {
            it.isbn13 in isbnsToSkip
          }
      }
      .forEach {
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
      .forEach {
        trackerConnector?.markWanted(it)
        it.connectorAudioBook.firstOrNull()?.isbn13?.let { isbn ->
          dbWriter.write(
            TrackerWishlistSyncStatus(
              isbn = isbn,
              status = WishlistItemSyncStatus.SUCCESS
            )
          )
        }
      }
  }

  private suspend fun processLibrary(overwrite: Boolean = false) {
    val localLibrary = libroClient.getLocalLibrary()

    localLibrary.audiobooks
      .let {
        if (serverInfo.limit == -1) {
          it
        } else {
          it.take(serverInfo.limit)
        }
      }
      .filter {
        if (!overwrite) {
          !downloadHistoryRepo.isDownloaded(it.isbn)
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
                  format = DownloadedFormat.MP3,
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
                      format = DownloadedFormat.M4B,
                      path = targetDir.path,
                    )
                  }

                  else -> {
                    lfdLogger.log("M4B download for ${book.title} failed, falling back to MP3")
                    downloadMp3sAndRename(book, targetDir)
                    LibroDownloadItem(
                      isbn = book.isbn,
                      format = DownloadedFormat.MP3,
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
                      format = DownloadedFormat.M4B,
                      path = targetDir.path,
                    )
                  }

                  else -> {
                    lfdLogger.log("M4B download for ${book.title} failed, falling back to conversion")
                    downloadMp3sAndRename(book, targetDir)
                    convertBookToM4b(book)
                    LibroDownloadItem(
                      isbn = book.isbn,
                      format = DownloadedFormat.M4B_CONVERTED,
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
      .forEach {
        dbWriter.write(
          DownloadItem(
            isbn = it.isbn,
            format = it.format,
            path = it.path
          )
        )
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

  private suspend fun HealthcheckApi.pingWithToken() = withContext(ioDispatcher) {
    hcToken?.let { if (it.isNotEmpty()) ping(it) }
  }

  private suspend fun HealthcheckApi.startMeasureWithToken() = withContext(ioDispatcher) {
    hcToken?.let { if (it.isNotEmpty()) start(it) }
  }
}