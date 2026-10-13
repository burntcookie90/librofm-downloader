package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libro.webhook.WebhookApi
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.DryRunTrackerConnector
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.connector.TrackerUnavailableException
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
import com.vishnurajeevan.libroabs.models.libro.Book
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
import kotlin.time.Duration
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
  trackerConnector: TrackerConnector?,
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
  /** In a dry run Hardcover is still read, but nothing is changed there. */
  private val tracker: TrackerConnector? =
    trackerConnector?.let { if (serverInfo.dryRun) DryRunTrackerConnector(it) { msg -> lfdLogger.i("Hardcover: $msg") } else it }

  @OptIn(ExperimentalTime::class)

  suspend fun run() {
    libroClient.fetchLoginData(serverInfo.libroUserName, serverInfo.libroPassword)
    try {
      tracker?.login()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Downloads don't need Hardcover; the sync will report it again.
      lfdLogger.i("Couldn't log in to Hardcover: ${e.message}")
    }
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
    tracker?.let { syncTracker(it, delay) }
    healthCheckClient.pingWithToken()
  }

  private suspend fun syncTracker(tracker: TrackerConnector, pause: Duration) {
    val failures = TrackerFailures()
    when (serverInfo.hardcoverSyncMode) {
      TrackerSyncMode.LIBRO_WISHLISTS_TO_HARDCOVER -> tracker.syncWishlistToConnector(failures)
      TrackerSyncMode.LIBRO_OWNED_TO_HARDCOVER -> tracker.syncOwned(failures)
      TrackerSyncMode.LIBRO_ALL_TO_HARDCOVER -> {
        tracker.syncWishlistToConnector(failures)
        tracker.syncOwned(failures)
      }
      TrackerSyncMode.HARDCOVER_WANT_TO_READ_TO_LIBRO -> tracker.syncWishlistFromConnector()
      TrackerSyncMode.ALL -> {
        tracker.syncWishlistToConnector(failures)
        delay(pause)
        tracker.syncOwned(failures)
        delay(pause)
        tracker.syncWishlistFromConnector()
      }
    }
    failures.throwIfAny()
  }

  /**
   * Collects per-book Hardcover failures so one bad book doesn't stop the rest. A [TrackerUnavailableException]
   * (a bad token, the rate limit) still stops the sync, since every other request would fail the same way.
   */
  private inner class TrackerFailures {
    private val messages = mutableListOf<String>()

    suspend fun <T> attempt(title: String, block: suspend () -> T): T? = try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: TrackerUnavailableException) {
      throw e
    } catch (e: Exception) {
      lfdLogger.i("Hardcover: $title: ${e.message}")
      messages += "$title: ${e.message}"
      null
    }

    fun throwIfAny() {
      if (messages.isNotEmpty()) error("${messages.size} Hardcover update(s) failed, first: ${messages.first()}")
    }
  }

  private suspend fun TrackerConnector.syncWishlistFromConnector() {
    lfdLogger.v("Syncing Wishlist from Tracker")
    val isbns = getWantedBooks()
      .flatMap { books -> books.connectorAudioBook.map { it.isbn13 } }
      .filterNotNull()
    if (serverInfo.dryRun) {
      lfdLogger.i("Dry run: would add these Hardcover Want to Read ISBNs to the libro.fm wishlist if missing: $isbns")
      return
    }
    libroClient.syncWishlist(isbns)
  }

  private fun List<ConnectorBook>.mapIsbns() = map { it.connectorAudioBook.map { it.isbn13 } }
    .flatten()
    .filterNotNull()

  /** Finds the Hardcover book for a libro.fm audiobook it has no edition of, and adds that audiobook edition. */
  private suspend fun TrackerConnector.createAudiobookEdition(audiobook: Book): ConnectorBook? {
    val author = audiobook.authors.firstOrNull() ?: run {
      lfdLogger.i("Hardcover: skipping ${audiobook.title}, libro.fm lists no author to match it by")
      return null
    }
    val trackerBook = searchByTitle(audiobook.title, author) ?: run {
      lfdLogger.v("Hardcover: no book called ${audiobook.title} by $author")
      return null
    }
    return createEdition(
      trackerBook.copy(
        releaseDate = audiobook.publication_date.toLocalDateTime(TimeZone.UTC).date,
        connectorAudioBook = listOf(ConnectorAudioBookEdition(id = "", isbn13 = audiobook.isbn))
      )
    )
  }

  private suspend fun TrackerConnector.syncWishlistToConnector(failures: TrackerFailures) {
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
      .forEach { failures.attempt(it.title) { markWanted(it) } }

    val editionsNotFound = isbnsToSync.minus(editions.map { it.connectorAudioBook.mapNotNull { it.isbn13 } }.flatten())
    libroWishlist.audiobooks
      .filter { it.isbn in editionsNotFound }
      .forEach { wished ->
        failures.attempt(wished.title) {
          val created = createAudiobookEdition(libroClient.fetchBookDetails(wished.isbn)) ?: return@attempt
          markWanted(created)
          created.connectorAudioBook.firstOrNull()?.isbn13?.let { isbn ->
            dbWriter.write(
              TrackerWishlistSyncStatus(
                isbn = isbn,
                status = WishlistItemSyncStatus.SUCCESS
              )
            )
          }
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

  private suspend fun TrackerConnector.syncOwned(failures: TrackerFailures) {
    val localLibrary = libroClient.getLocalLibrary()
    lfdLogger.v("Syncing Owned to Tracker")
    val audiobooks = localLibrary.audiobooks.filter { it.isbn !in serverInfo.skipTrackingIsbns }
    val isbn13s = audiobooks.map { it.isbn }
    val editions: List<ConnectorBook> = getEditions(isbn13s)
    val editionsNotFound = isbn13s.minus(editions.map { it.connectorAudioBook.mapNotNull { it.isbn13 } }.flatten())
    val ownedIsbns = getOwnedBooks().mapIsbns()

    editions
      .filterNot {
        it.connectorAudioBook
          .mapNotNull { it.isbn13 }
          .any { it in ownedIsbns }
      }
      .forEach { failures.attempt(it.title) { markOwned(it) } }
    audiobooks
      .filter { it.isbn in editionsNotFound }
      .forEach { audiobook ->
        failures.attempt(audiobook.title) { createAudiobookEdition(audiobook)?.let { markOwned(it) } }
      }
  }

  private suspend fun HealthcheckApi.pingWithToken() = withContext(ioDispatcher) {
    hcToken?.let { if (it.isNotEmpty()) ping(it) }
  }

  private suspend fun HealthcheckApi.startMeasureWithToken() = withContext(ioDispatcher) {
    hcToken?.let { if (it.isNotEmpty()) start(it) }
  }
}