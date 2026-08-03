package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libro.webhook.WebhookApi
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.converter.ffmpeg.FfmpegClient
import com.vishnurajeevan.libroabs.db.repo.DownloadHistoryRepo
import com.vishnurajeevan.libroabs.db.repo.TrackerCreatedEditionRepo
import com.vishnurajeevan.libroabs.db.repo.TrackerWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.DownloadItem
import com.vishnurajeevan.libroabs.db.writer.DownloadPdfExtraItem
import com.vishnurajeevan.libroabs.db.writer.TrackerCreatedEdition
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
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import com.vishnurajeevan.libroabs.server.route.RouteHandler
import com.vishnurajeevan.libroabs.server.setupServer
import com.vishnurajeevan.libroabs.storage.models.LibroDownloadItem
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.kevincianfarini.cardiologist.fixedPeriodPulse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

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
  private val trackerCreatedEditionRepo: TrackerCreatedEditionRepo,
  private val webhookApi: WebhookApi,
  private val routeHandlerMap: Map<KClass<*>, RouteHandler<*>>
) {
  @OptIn(ExperimentalTime::class)

  suspend fun run() {
    libroClient.fetchLoginData(serverInfo.libroUserName, serverInfo.libroPassword)
    trackerConnector?.login()

    appScope.launch {
      fullUpdate(delayForInitial = !serverInfo.dryRun)
    }

    appScope.launch {
      lfdLogger.v("Sync Interval: ${serverInfo.syncInterval}")
      val syncIntervalTimeUnit = when (serverInfo.syncInterval) {
        "h" -> 1.hours
        "d" -> 1.days
        "w" -> 7.days
        else -> error("Unhandled sync interval")
      }

      Clock.System.fixedPeriodPulse(syncIntervalTimeUnit)
        .beat {
          lfdLogger.i("Checking library on pulse!")
          supervisorScope {
            try {
              fullUpdate()
            } catch (e: Exception) {
              lfdLogger.i("Pulse update failed: ${e.message}")
            }
          }
        }
    }

    setupServer(
      onUpdate = { fullUpdate(overwrite = it) },
      serverInfo = serverInfo,
      routeHandlerMap = routeHandlerMap,
    ).start(wait = true)
  }

  /**
   * Guards against overlapping library syncs.
   *
   * `/update` is an unauthenticated GET, so anything that can reach the port — including a page the
   * user happens to be visiting, via a plain `<img src>` — can trigger a sync, and `?overwrite=true`
   * re-downloads the *entire* library. With no guard, repeated calls stacked concurrent full syncs
   * on top of each other and multiplied the load we put on libro.fm. Concurrent requests now no-op
   * instead of queueing up another pass.
   */
  private val updateInFlight = Mutex()

  private suspend fun fullUpdate(
    delayForInitial: Boolean = false,
    overwrite: Boolean = false
  ) {
    if (!updateInFlight.tryLock()) {
      lfdLogger.i("An update is already running, ignoring this request")
      return
    }
    try {
      runFullUpdate(delayForInitial, overwrite)
    } finally {
      updateInFlight.unlock()
    }
  }

  private suspend fun runFullUpdate(
    delayForInitial: Boolean,
    overwrite: Boolean
  ) {
    val delay = if (delayForInitial || overwrite) 1.minutes else 0.minutes
    healthCheckClient.startMeasureWithToken()
    libroClient.fetchLibrary()
    delay(delay)
    processLibrary(overwrite)
    delay(delay)
    if (trackerConnector != null) {
      when (serverInfo.hardcoverSyncMode) {
        TrackerSyncMode.LIBRO_WISHLISTS_TO_HARDCOVER -> {
          trackerConnector.syncWishlistToConnector()
        }

        TrackerSyncMode.LIBRO_OWNED_TO_HARDCOVER -> {
          syncOwned()
        }

        TrackerSyncMode.LIBRO_ALL_TO_HARDCOVER -> {
          trackerConnector.syncWishlistToConnector()
          syncOwned()
        }

        TrackerSyncMode.HARDCOVER_WANT_TO_READ_TO_LIBRO -> {
          trackerConnector.syncWishlistFromConnector()
        }

        TrackerSyncMode.ALL -> {
          trackerConnector.syncWishlistToConnector()
          delay(delay)
          syncOwned()
          delay(delay)
          trackerConnector.syncWishlistFromConnector()
        }
      }
    }
    healthCheckClient.pingWithToken()
  }

  private suspend fun TrackerConnector.syncWishlistFromConnector() {
    lfdLogger.v("Syncing Wishlist from Tracker")
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
    lfdLogger.v("Syncing Wishlist to Tracker")
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
    createMissingEditions(editionsNotFound) { libroClient.fetchBookDetails(it) }
      .forEach { (isbn, created) ->
        markWanted(created)
        dbWriter.write(
          TrackerWishlistSyncStatus(
            isbn = isbn,
            status = WishlistItemSyncStatus.SUCCESS
          )
        )
      }
  }

  /**
   * Create tracker editions for libro ISBNs that the tracker does not know about yet, and return the
   * ISBN paired with each edition that was created.
   *
   * `insert_edition` writes into the tracker's *shared* book database, and the previous
   * implementation re-derived what to create purely from "does an ISBN lookup find it?". Any insert
   * that did not immediately become queryable by ISBN — held for moderation, silently rejected, a
   * locked book, an ISBN normalized differently on their side — produced a brand new duplicate
   * edition on every sync, forever, unattended.
   *
   * The guard against that distinguishes two outcomes, because they need opposite handling:
   *
   * - **We called `createEdition`.** A write may have landed regardless of what came back, so the
   *   ISBN is recorded and never retried.
   * - **Search found no match.** Nothing was written, so there is no duplicate to create. These are
   *   retried after [UNMATCHED_RETRY_INTERVAL] rather than written off permanently — the tracker's
   *   catalog grows, and a book it cannot match today may match later. Notably, a tracker whose book
   *   records are English-only will never match a non-English audiobook, and those should not be
   *   abandoned on the strength of one lookup.
   *
   * [resolveBook] is only invoked for ISBNs that survive the guard so that we do not spend a libro.fm
   * request per book on work we are going to discard.
   */
  private suspend fun TrackerConnector.createMissingEditions(
    isbns: List<String>,
    resolveBook: suspend (String) -> Book,
  ): List<Pair<String, ConnectorBook>> {
    if (isbns.isEmpty()) return emptyList()
    val now = Clock.System.now()
    val toSkip = trackerCreatedEditionRepo.getIsbnsToSkip(
      unmatchedRetryCutoffEpochSeconds = (now - UNMATCHED_RETRY_INTERVAL).epochSeconds
    )

    val (toAttempt, skipped) = isbns.partition { it !in toSkip }
    if (skipped.isNotEmpty()) {
      lfdLogger.v("Skipping edition creation for ${skipped.size} ISBN(s) handled on a previous run")
    }

    return toAttempt.mapNotNull { isbn ->
      var writeAttempted = false
      val created = runCatching {
        val audiobook = resolveBook(isbn)
        val trackerBook = searchByTitle(audiobook.title, audiobook.authors.first())
        if (trackerBook == null) {
          lfdLogger.v("No tracker match for ${audiobook.title} ($isbn), not creating an edition")
          null
        } else {
          // Set before the call, not after, so a failure partway through is still treated as a
          // possible write.
          writeAttempted = true
          createEdition(
            trackerBook.copy(
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
        .onFailure {
          if (it is CancellationException) throw it
          lfdLogger.i("Edition creation failed for $isbn: ${it.message}")
        }
        .getOrNull()

      withContext(NonCancellable) {
        dbWriter.write(
          TrackerCreatedEdition(
            isbn = isbn,
            writeAttempted = writeAttempted,
            attemptedAtEpochSeconds = now.epochSeconds
          )
        )
      }

      created?.let { isbn to it }
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
        if (overwrite) {
          true
        } else {
          val isDownloaded = downloadHistoryRepo.isDownloaded(it.isbn)
          lfdLogger.v("Download history | ${it.isbn} is downloaded: $isDownloaded")
          !isDownloaded
        }
      }
      .map { book ->
        processingScope.async {
          processingSemaphore.withPermit {
            val targetDir = targetDir(book).also { it.mkdirs() }
            lfdLogger.v("Downloading ${book.title}")
            val item = when (serverInfo.format) {
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
                    lfdLogger.v("M4B download for ${book.title} failed, falling back to MP3")
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
                    lfdLogger.v("M4B download for ${book.title} failed, falling back to conversion")
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
            withContext(NonCancellable) {
              dbWriter.write(
                DownloadItem(
                  isbn = item.isbn,
                  format = item.format,
                  path = item.path
                )
              )
            }
            item
          }
        }
      }
      .awaitAll()
      .also { items ->
        if (items.isNotEmpty()) {
          serverInfo.webhookUrls.forEach { webhookApi.postToWebhook(it) }
        }
      }

    if (serverInfo.downloadExtras) {
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
            !downloadHistoryRepo.pdfExtrasDownloaded(it.isbn)
          } else {
            true
          }
        }
        .map { book ->
          processingScope.async {
            processingSemaphore.withPermit {
              val targetDir = targetDir(book).also { it.mkdirs() }
              libroClient.downloadPdfExtras(
                isbn = book.isbn,
                data = book.audiobook_info.pdf_extras,
                targetDirectory = targetDir
              )
              val item = DownloadPdfExtraItem(
                isbn = book.isbn
              )
              withContext(NonCancellable) {
                dbWriter.write(item)
              }
              item
            }
          }
        }
        .awaitAll()
        .also { items ->
          if (items.isNotEmpty()) {
            serverInfo.webhookUrls.forEach { webhookApi.postToWebhook(it) }
          }
        }
    }

  }

  private suspend fun syncOwned() {
    val localLibrary = libroClient.getLocalLibrary()
    lfdLogger.v("Syncing Owned to Tracker")
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

    val booksByIsbn = localLibrary.audiobooks.associateBy { it.isbn }
    trackerConnector
      ?.createMissingEditions(editionsNotFound.filterNot { it in serverInfo.skipTrackingIsbns }) {
        booksByIsbn.getValue(it)
      }
      ?.forEach { (_, created) -> trackerConnector.markOwned(created) }
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
      lfdLogger.v("Book ${book.title} is not downloaded yet!")
      targetDir.mkdirs()
      downloadMetaData = downloadBookAsMp3s(book, targetDir)
    }

    val chapterFiles =
      targetDir.listFiles { file -> file.extension == "mp3" }
    if (chapterFiles == null || chapterFiles.isEmpty()) {
      lfdLogger.v("Book ${book.title} does not have mp3 files downloaded. Downloading the book again.")
      downloadMetaData = downloadBookAsMp3s(book, targetDir)
    }

    if (downloadMetaData == null) {
      downloadMetaData = libroClient.fetchMp3DownloadMetadata(book.isbn)
    }

    lfdLogger.v("Converting ${book.title} from mp3 to m4b.")

    if (!serverInfo.dryRun) {
      ffmpegClient.convertBookToM4b(
        book = book,
        tracks = downloadMetaData.tracks,
        targetDirectory = targetDir,
        audioQuality = serverInfo.audioQuality
      )

      lfdLogger.v("Deleting obsolete mp3 files for ${book.title}")

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

  private companion object {
    /**
     * How long to leave a book alone after the tracker had no match for it. Long enough that a large
     * unmatched library is not re-searched on every pulse, short enough that catalog additions get
     * picked up.
     */
    val UNMATCHED_RETRY_INTERVAL = 30.days
  }
}