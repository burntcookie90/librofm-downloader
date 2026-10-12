package com.vishnurajeevan.libroabs.download

import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.AttemptStatus
import com.vishnurajeevan.libroabs.models.server.AttemptTrigger
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.DownloadedFormat
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.SyncTrigger
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import com.vishnurajeevan.libroabs.server.ui.BookState
import com.vishnurajeevan.libroabs.server.ui.EnqueueOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class RealWebUiBackendTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val tmp = kotlin.io.path.createTempDirectory("ui-test").toFile()
  private val libro = FakeLibroFm()
  private val attempts = FakeAttempts()
  private val history = FakeHistory()
  private val writer = FakeWriter(history)
  private val logger = object : Logger {
    override fun v(msg: String) {}
    override fun i(msg: String) {}
  }
  private val info = ServerInfo(
    libroUserName = "u", libroPassword = "p", port = 0, dataDir = "", mediaDir = tmp.path, syncInterval = "d", parallelCount = 1,
    dryRun = false, renameChapters = false, writeTitleTag = false, format = BookFormat.M4B_MP3_FALLBACK, downloadExtras = false,
    logLevel = ApplicationLogLevel.NONE, limit = -1, pathPattern = "", healthCheckHost = "", healthCheckId = null, trackerToken = "t",
    trackerEndpoint = "", ffmpegPath = "", ffprobePath = "", audioQuality = "128k", skipTrackingIsbns = emptyList(),
    hardcoverSyncMode = TrackerSyncMode.ALL,
  )
  private val sync = SyncController(scope, logger)
  private val downloads = DownloadService(
    info, FakeConverter(), libro, scope, Dispatchers.IO, Semaphore(2), logger, attempts, writer, { File(tmp, it.isbn) },
  )
  private val backend = RealWebUiBackend(
    serverInfo = info,
    libroClient = libro,
    downloadService = downloads,
    syncController = sync,
    attemptRepo = attempts,
    downloadHistoryRepo = history,
    dbWriter = writer,
    libroWishlistRepo = FakeWishlist(mapOf("111" to true)),
    trackerWishlistRepo = FakeWishlist(mapOf("222" to false)),
  )

  @AfterTest
  fun cleanup() {
    scope.cancel()
    tmp.deleteRecursively()
  }

  private fun <T> run(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

  private suspend fun settle(isbn: String) {
    while (downloads.isActive(isbn)) delay(5)
  }

  @Test
  fun libraryEntriesCombineHistoryAndLatestAttempt() = run {
    libro.library = listOf(testBook("1"), testBook("2"), testBook("3"), testBook("4"))
    history.map["1"] = com.vishnurajeevan.libroabs.models.server.ItemDownloadHistory("1", DownloadedFormat.MP3, "/p", false)
    attempts.create("2", "Book 2", AttemptTrigger.SCHEDULED, null, 1).also {
      attempts.markFinished(it, AttemptStatus.FAILED, "boom", null, 2)
    }
    attempts.create("3", "Book 3", AttemptTrigger.MANUAL, null, 1)

    val states = backend.libraryEntries().associate { it.book.isbn to it.state }
    assertEquals(
      mapOf("1" to BookState.DOWNLOADED, "2" to BookState.FAILED, "3" to BookState.QUEUED, "4" to BookState.NOT_DOWNLOADED),
      states,
    )
    val stats = backend.stats()
    assertEquals(listOf(4, 1, 1, 1, 1), listOf(stats.totalBooks, stats.downloaded, stats.notDownloaded, stats.failed, stats.active))
    assertEquals(null, backend.libraryEntry("nope"))
    assertEquals("boom", backend.libraryEntry("2")!!.latestAttempt!!.error)
  }

  @Test
  fun manualDownloadRunsAndUpdatesState() = run {
    libro.library = listOf(testBook("1"))
    val outcome = backend.enqueueDownload("1", null)
    assertIs<EnqueueOutcome.Queued>(outcome)
    settle("1")
    assertEquals(BookState.DOWNLOADED, backend.libraryEntry("1")!!.state)
    assertEquals(AttemptTrigger.MANUAL, backend.attemptsFor("1").single().trigger)
  }

  @Test
  fun alreadyActiveDownloadsAreNotDoubleQueued() = run {
    libro.library = listOf(testBook("1"))
    libro.gate = CompletableDeferred()
    val first = backend.enqueueDownload("1", null)
    val second = backend.enqueueDownload("1", null)
    assertIs<EnqueueOutcome.Queued>(first)
    assertIs<EnqueueOutcome.AlreadyActive>(second)
    assertEquals(0, backend.enqueueDownloads(listOf("1", "1"), null), "already active, so nothing new is queued")
    assertEquals(1, attempts.rows.size)
    libro.gate!!.complete(Unit)
    settle("1")
  }

  @Test
  fun unknownIsbnIsLookedUpAndFailuresAreExplained() = run {
    libro.library = emptyList()
    libro.details = { error("HTTP 404") }
    val failure = backend.enqueueDownload("999", null)
    assertIs<EnqueueOutcome.Failure>(failure)
    assertTrue(failure.message.contains("999") && failure.message.contains("HTTP 404"), failure.message)
    assertTrue(attempts.rows.isEmpty())

    libro.details = { isbn -> testBook(isbn, "Found It") }
    assertIs<EnqueueOutcome.Queued>(backend.enqueueDownload("999", null))
    settle("999")
    assertEquals("Found It", attempts.rows.single().title)
  }

  @Test
  fun retryReusesTheRequestedFormatAndMarksTheTrigger() = run {
    libro.library = listOf(testBook("1"))
    libro.m4bAvailable = false
    libro.failMp3With = java.io.IOException("down")
    backend.enqueueDownload("1", BookFormat.MP3)
    settle("1")
    val failed = attempts.rows.single()
    assertEquals(AttemptStatus.FAILED, failed.status)

    libro.failMp3With = null
    assertIs<EnqueueOutcome.Queued>(backend.retryAttempt(failed.id))
    settle("1")
    val retry = attempts.rows.last()
    assertEquals(AttemptTrigger.RETRY, retry.trigger)
    assertEquals(BookFormat.MP3, retry.requestedFormat)
    assertEquals(AttemptStatus.SUCCEEDED, retry.status)
    assertEquals(BookState.DOWNLOADED, backend.libraryEntry("1")!!.state)
    assertEquals(EnqueueOutcome.NotFound, backend.retryAttempt(12345))
  }

  @Test
  fun retryFailedOnlyTouchesFailedBooks() = run {
    libro.library = listOf(testBook("ok"), testBook("failed"), testBook("fresh"))
    history.map["ok"] = com.vishnurajeevan.libroabs.models.server.ItemDownloadHistory("ok", DownloadedFormat.M4B, "/p", false)
    attempts.create("failed", "t", AttemptTrigger.SCHEDULED, null, 1).also { attempts.markFinished(it, AttemptStatus.FAILED, "e", null, 2) }

    assertEquals(1, backend.retryFailed())
    settle("failed")
    assertEquals(listOf("failed"), attempts.rows.filter { it.trigger == AttemptTrigger.RETRY }.map { it.isbn })
    assertEquals(BookState.NOT_DOWNLOADED, backend.libraryEntry("fresh")!!.state)
  }

  @Test
  fun downloadMissingSkipsDownloadedBooks() = run {
    libro.library = listOf(testBook("have"), testBook("want1"), testBook("want2"))
    history.map["have"] = com.vishnurajeevan.libroabs.models.server.ItemDownloadHistory("have", DownloadedFormat.M4B, "/p", false)
    assertEquals(2, backend.downloadMissing())
    settle("want1"); settle("want2")
    assertEquals(setOf("want1", "want2"), attempts.rows.map { it.isbn }.toSet())
    assertEquals(0, backend.downloadMissing(), "everything is downloaded now")
  }

  @Test
  fun forgetRemovesFromHistory() = run {
    libro.library = listOf(testBook("1"))
    history.map["1"] = com.vishnurajeevan.libroabs.models.server.ItemDownloadHistory("1", DownloadedFormat.M4B, "/p", false)
    backend.forgetDownload("1")
    assertEquals(BookState.NOT_DOWNLOADED, backend.libraryEntry("1")!!.state)
  }

  @Test
  fun clearingAttemptsKeepsActiveOnes() = run {
    libro.library = listOf(testBook("1"))
    val old = attempts.create("old", "t", AttemptTrigger.SCHEDULED, null, now = 1)
    attempts.markFinished(old, AttemptStatus.SUCCEEDED, null, DownloadedFormat.M4B, 2)
    attempts.create("live", "t", AttemptTrigger.SCHEDULED, null, now = System.currentTimeMillis())
    assertEquals(1, backend.clearAttempts(olderThanDays = 30))
    assertEquals(0, backend.clearAttempts(olderThanDays = null))
    assertEquals(listOf("live"), attempts.rows.map { it.isbn })
  }

  @Test
  fun wishlistOverviewReflectsRepos() = run {
    val w = backend.wishlist()
    assertEquals(mapOf("111" to true), w.libroSynced)
    assertEquals(mapOf("222" to false), w.trackerSynced)
    assertTrue(w.trackerEnabled)
  }

  @Test
  fun syncRequestsRunOnceAndAreTracked() = run {
    val gate = CompletableDeferred<Unit>()
    sync.runner = { trigger, overwrite -> sync.track(trigger, overwrite) { gate.await() } }
    assertTrue(backend.requestSync(overwrite = true))
    // the running state is visible immediately, before the call returns
    assertTrue(backend.syncSnapshot().running)
    assertEquals(SyncTrigger.MANUAL, backend.syncSnapshot().trigger)
    assertTrue(backend.syncSnapshot().overwrite)
    assertTrue(!backend.requestSync(false), "a second sync can't start while one is running")
    gate.complete(Unit)
    while (backend.syncSnapshot().running) delay(5)
    val done = backend.syncSnapshot()
    assertEquals(null, done.lastError)
    assertTrue(done.lastFinishedAt != null)
  }

  @Test
  fun scheduledSyncsSkipWhileAnotherRunsAndBlockManualOnes() = run {
    val gate = CompletableDeferred<Unit>()
    sync.runner = { trigger, overwrite -> sync.track(trigger, overwrite) { gate.await() } }
    assertTrue(backend.requestSync(false))
    var ran = false
    assertFalse(sync.runIfIdle(SyncTrigger.SCHEDULED) { ran = true })
    assertFalse(ran)
    gate.complete(Unit)
    while (backend.syncSnapshot().running) delay(5)

    val scheduled = CompletableDeferred<Unit>()
    val job = CoroutineScope(Dispatchers.Default).launch { sync.runIfIdle(SyncTrigger.SCHEDULED) { sync.track(SyncTrigger.SCHEDULED, false) { scheduled.await() } } }
    while (!backend.syncSnapshot().running) delay(5)
    assertFalse(backend.requestSync(false), "a manual sync can't start during a scheduled one")
    scheduled.complete(Unit)
    job.join()
    assertTrue(backend.requestSync(false))
  }

  @Test
  fun failedSyncsAreRecorded() = run {
    sync.runner = { trigger, overwrite -> sync.track(trigger, overwrite) { error("libro.fm is down") } }
    assertTrue(backend.requestSync(false))
    while (backend.syncSnapshot().running) delay(5)
    assertTrue(backend.syncSnapshot().lastError!!.contains("libro.fm is down"))
  }

  @Test
  fun syncWithoutRunnerIsRefused() = run {
    assertTrue(!backend.requestSync(false))
  }
}
