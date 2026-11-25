package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libro.webhook.WebhookApi
import com.vishnurajeevan.libroabs.db.repo.DownloadHistoryRepo
import com.vishnurajeevan.libroabs.download.DownloadService
import com.vishnurajeevan.libroabs.download.SyncController
import com.vishnurajeevan.libroabs.healthcheck.HealthcheckApi
import com.vishnurajeevan.libroabs.libro.LibroApiHandler
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.App
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.server.AttemptStatus
import com.vishnurajeevan.libroabs.models.server.AttemptTrigger
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.SyncTrigger
import com.vishnurajeevan.libroabs.server.route.RouteHandler
import com.vishnurajeevan.libroabs.server.setupServer
import com.vishnurajeevan.libroabs.server.ui.WebUiBackend
import com.vishnurajeevan.libroabs.tracker.HardcoverSync
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.SingleIn
import io.github.kevincianfarini.cardiologist.fixedPeriodPulse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
  private val hardcoverSync: HardcoverSync,
  @App private val appScope: CoroutineScope,
  @Io private val processingScope: CoroutineScope,
  @Io private val ioDispatcher: CoroutineDispatcher,
  private val processingSemaphore: Semaphore,
  private val lfdLogger: Logger,
  private val downloadHistoryRepo: DownloadHistoryRepo,
  private val downloadService: DownloadService,
  private val syncController: SyncController,
  private val webUiBackend: WebUiBackend,
  private val webhookApi: WebhookApi,
  private val routeHandlerMap: Map<KClass<*>, RouteHandler<*>>
) {
  @OptIn(ExperimentalTime::class)

  suspend fun run() {
    libroClient.fetchLoginData(serverInfo.libroUserName, serverInfo.libroPassword)
    try {
      hardcoverSync.login()
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

    val hardcoverInterval = serverInfo.hardcoverSyncInterval
    if (hardcoverSync.enabled && hardcoverInterval > Duration.ZERO) {
      appScope.launch {
        Clock.System.fixedPeriodPulse(hardcoverInterval)
          .beat {
            // Both pulses count from startup, so they can fire together; give the library sync, which syncs
            // Hardcover too, the first go.
            delay(1.minutes)
            supervisorScope {
              try {
                syncController.runIfIdle(SyncTrigger.SCHEDULED, "Hardcover sync") { hardcoverSync.syncAutomatically() }
              } catch (e: Exception) {
                lfdLogger.i("Hardcover sync failed: ${e.message}")
              }
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
    // A failed download doesn't hold Hardcover back; both are reported once both have run.
    val failures = listOfNotNull(
      failure { processLibrary(overwrite) },
      if (hardcoverSync.enabled) {
        delay(delay)
        failure { hardcoverSync.syncAutomatically() }
      } else {
        null
      },
    )
    if (failures.isNotEmpty()) error(failures.joinToString("; ") { it.message ?: it::class.simpleName.orEmpty() })
    healthCheckClient.pingWithToken()
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

  private suspend fun failure(block: suspend () -> Unit): Exception? = try {
    block()
    null
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    e
  }

  private suspend fun HealthcheckApi.pingWithToken() = withContext(ioDispatcher) {
    hcToken?.let { if (it.isNotEmpty()) ping(it) }
  }

  private suspend fun HealthcheckApi.startMeasureWithToken() = withContext(ioDispatcher) {
    hcToken?.let { if (it.isNotEmpty()) start(it) }
  }
}