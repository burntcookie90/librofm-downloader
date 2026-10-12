package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libro.webhook.WebhookApi
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.db.repo.DownloadHistoryRepo
import com.vishnurajeevan.libroabs.db.repo.TrackerWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.download.DownloadService
import com.vishnurajeevan.libroabs.download.SyncController
import com.vishnurajeevan.libroabs.db.writer.TrackerWishlistSyncStatus
import com.vishnurajeevan.libroabs.healthcheck.HealthcheckApi
import com.vishnurajeevan.libroabs.libro.LibroApiHandler
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.App
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.libro.WishlistItemSyncStatus
import com.vishnurajeevan.libroabs.models.server.AttemptStatus
import com.vishnurajeevan.libroabs.models.server.AttemptTrigger
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.SyncTrigger
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import com.vishnurajeevan.libroabs.server.route.RouteHandler
import com.vishnurajeevan.libroabs.server.setupServer
import com.vishnurajeevan.libroabs.server.ui.WebUiBackend
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.github.kevincianfarini.cardiologist.fixedPeriodPulse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
  private val libroClient: LibroApiHandler,
  private val trackerConnector: TrackerConnector?,
  @App private val appScope: CoroutineScope,
  @Io private val processingScope: CoroutineScope,
  @Io private val ioDispatcher: CoroutineDispatcher,
  private val processingSemaphore: Semaphore,
  private val lfdLogger: Logger,
  private val downloadHistoryRepo: DownloadHistoryRepo,
  private val downloadService: DownloadService,
  private val syncController: SyncController,
  private val webUiBackend: WebUiBackend,
  private val dbWriter: DbWriter,
  private val trackerWishlistSyncStatusRepo: TrackerWishlistSyncStatusRepo,
  private val webhookApi: WebhookApi,
  private val routeHandlerMap: Map<KClass<*>, RouteHandler<*>>
) {
  @OptIn(ExperimentalTime::class)

  suspend fun run() {
    libroClient.fetchLoginData(serverInfo.libroUserName, serverInfo.libroPassword)
    trackerConnector?.login()
    downloadService.recoverInterrupted()

    syncController.runner = { trigger, overwrite -> fullUpdate(overwrite = overwrite, trigger = trigger) }

    appScope.launch {
      syncController.runIfIdle(SyncTrigger.STARTUP) {
        fullUpdate(delayForInitial = !serverInfo.dryRun, trigger = SyncTrigger.STARTUP)
      }
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
              syncController.runIfIdle(SyncTrigger.SCHEDULED) { fullUpdate(trigger = SyncTrigger.SCHEDULED) }
            } catch (e: Exception) {
              lfdLogger.i("Pulse update failed: ${e.message}")
            }
          }
        }
    }

    setupServer(
      onUpdate = { syncController.requestSync(SyncTrigger.API, it) },
      serverInfo = serverInfo,
      webUiBackend = webUiBackend,
      routeHandlerMap = routeHandlerMap,
    ).start(wait = true)
  }

  private suspend fun fullUpdate(
    delayForInitial: Boolean = false,
    overwrite: Boolean = false,
    trigger: SyncTrigger = SyncTrigger.API,
  ) = syncController.track(trigger, overwrite) {
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

    val books = localLibrary.audiobooks
      .let {
        if (serverInfo.limit == -1) {
          it
        } else {
          it.take(serverInfo.limit)
        }
      }

    val toDownload = books.filter {
      if (overwrite) {
        true
      } else {
        val isDownloaded = downloadHistoryRepo.isDownloaded(it.isbn)
        lfdLogger.v("Download history | ${it.isbn} is downloaded: $isDownloaded")
        !isDownloaded
      }
    }

    // Every book gets its own recorded attempt. A failing book doesn't stop the others.
    val statuses = toDownload
      .map { downloadService.enqueue(it, AttemptTrigger.SCHEDULED) }
      .map { it.result }
      .awaitAll()

    if (statuses.any { it == AttemptStatus.SUCCEEDED }) {
      serverInfo.webhookUrls.forEach { webhookApi.postToWebhook(it) }
    }

    if (serverInfo.downloadExtras) {
      val extrasBooks = books.filter {
        if (!overwrite) {
          !downloadHistoryRepo.pdfExtrasDownloaded(it.isbn)
        } else {
          true
        }
      }
      extrasBooks
        .map { book ->
          processingScope.async {
            processingSemaphore.withPermit {
              downloadService.downloadExtras(book)
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

    val failed = statuses.count { it == AttemptStatus.FAILED }
    if (failed > 0) {
      // Surface the failure so health checks and sync status reflect it. Details live on each attempt.
      error("$failed of ${statuses.size} download(s) failed")
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

  private suspend fun HealthcheckApi.pingWithToken() = withContext(ioDispatcher) {
    hcToken?.let { if (it.isNotEmpty()) ping(it) }
  }

  private suspend fun HealthcheckApi.startMeasureWithToken() = withContext(ioDispatcher) {
    hcToken?.let { if (it.isNotEmpty()) start(it) }
  }
}