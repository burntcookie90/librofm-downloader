package com.vishnurajeevan.libroabs.download

import com.vishnurajeevan.libroabs.converter.ffmpeg.M4bConverter
import com.vishnurajeevan.libroabs.db.repo.AttemptPage
import com.vishnurajeevan.libroabs.db.repo.DownloadAttemptRepo
import com.vishnurajeevan.libroabs.db.writer.DbWrite
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.DownloadItem
import com.vishnurajeevan.libroabs.db.writer.DownloadPdfExtraItem
import com.vishnurajeevan.libroabs.libro.LibroFmBooks
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.BookInfo
import com.vishnurajeevan.libroabs.models.libro.DownloadPart
import com.vishnurajeevan.libroabs.models.libro.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.models.libro.PdfExtra
import com.vishnurajeevan.libroabs.models.libro.Tracks
import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.AttemptStatus
import com.vishnurajeevan.libroabs.models.server.AttemptTrigger
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.DownloadAttempt
import com.vishnurajeevan.libroabs.models.server.DownloadedFormat
import com.vishnurajeevan.libroabs.models.server.M4bMetadata
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadServiceTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val tmp = kotlin.io.path.createTempDirectory("dl-test").toFile()

  @AfterTest
  fun cleanup() {
    scope.cancel()
    tmp.deleteRecursively()
  }

  private val downloader = FakeLibroFm()
  private val converter = FakeConverter()
  private val writer = FakeWriter()
  private val attempts = FakeAttempts()
  private val importer = FakeImporter()

  private fun info(format: BookFormat = BookFormat.M4B_MP3_FALLBACK, extras: Boolean = false, renameChapters: Boolean = false) = ServerInfo(
    libroUserName = "u", libroPassword = "p", port = 0, dataDir = "", mediaDir = tmp.path, syncInterval = "d", parallelCount = 1,
    dryRun = false, renameChapters = renameChapters, writeTitleTag = false, format = format, downloadExtras = extras,
    logLevel = ApplicationLogLevel.NONE, limit = -1, pathPattern = "", healthCheckHost = "", healthCheckId = null, trackerToken = null,
    trackerEndpoint = "", ffmpegPath = "", ffprobePath = "", audioQuality = "128k", skipTrackingIsbns = emptyList(),
    hardcoverSyncMode = TrackerSyncMode.ALL,
  )

  private fun service(info: ServerInfo = info(), parallel: Int = 2, processingScope: CoroutineScope = scope) = DownloadService(
    serverInfo = info,
    ffmpegClient = converter,
    libroClient = downloader,
    processingScope = processingScope,
    ioDispatcher = Dispatchers.IO,
    processingSemaphore = Semaphore(parallel),
    lfdLogger = object : Logger {
      override fun v(msg: String) {}
      override fun i(msg: String) {}
    },
    attemptRepo = attempts,
    dbWriter = writer,
    targetDir = { File(tmp, it.isbn) },
    importer = importer,
  )

  private fun book(isbn: String, title: String = "Book $isbn") = testBook(isbn, title)

  private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

  // ---- tests ----

  @Test
  fun successfulDownloadIsRecordedAndHistoryWritten() = await {
    val service = service()
    val r = service.enqueue(book("1"), AttemptTrigger.MANUAL)
    assertEquals(AttemptStatus.SUCCEEDED, r.result.await())

    val attempt = attempts.get(r.attemptId)!!
    assertEquals(AttemptStatus.SUCCEEDED, attempt.status)
    assertEquals(DownloadedFormat.M4B, attempt.format)
    assertNull(attempt.error)
    assertNotNull(attempt.startedAt)
    assertNotNull(attempt.finishedAt)
    val item = writer.writes.filterIsInstance<DownloadItem>().single()
    assertEquals("1", item.isbn)
    assertEquals(DownloadedFormat.M4B, item.format)
    assertTrue(File(tmp, "1").isDirectory)
    assertFalse(service.isActive("1"))
  }

  @Test
  fun missingM4bFallsBackToMp3() = await {
    downloader.m4bAvailable = false
    val r = service().enqueue(book("2"), AttemptTrigger.SCHEDULED)
    assertEquals(AttemptStatus.SUCCEEDED, r.result.await())
    assertEquals(DownloadedFormat.MP3, attempts.get(r.attemptId)!!.format)
    assertTrue("mp3s" in downloader.calls)
  }

  @Test
  fun renamingOnlyTouchesTheExtractedChapters() = await {
    downloader.m4bAvailable = false
    downloader.tracks = listOf(Tracks(1, "Opening"), Tracks(2, "Ending"))
    downloader.mp3Names = listOf("part-b.mp3", "part-a.mp3")
    val dir = File(tmp, "4").apply { mkdirs() }
    File(dir, "cover.jpg").writeText("cover")
    File(dir, "Extras.pdf").writeText("pdf")
    val r = service(info(BookFormat.MP3, renameChapters = true)).enqueue(book("4", "Tale"), AttemptTrigger.MANUAL)
    assertEquals(AttemptStatus.SUCCEEDED, r.result.await())
    assertEquals(
      listOf("1 - Tale - Opening.mp3", "2 - Tale - Ending.mp3", "Extras.pdf", "cover.jpg"),
      dir.list()!!.sorted(),
    )
    // chapters are assigned in name order
    assertEquals("part-a.mp3", File(dir, "1 - Tale - Opening.mp3").readText())
  }

  @Test
  fun chaptersAreLeftAloneWhenTheyDontMatchTheTracks() = await {
    downloader.m4bAvailable = false
    downloader.tracks = listOf(Tracks(1, "Opening"), Tracks(2, "Ending"))
    downloader.mp3Names = listOf("only.mp3")
    val r = service(info(BookFormat.MP3, renameChapters = true)).enqueue(book("5"), AttemptTrigger.MANUAL)
    assertEquals(AttemptStatus.SUCCEEDED, r.result.await())
    assertEquals(listOf("only.mp3"), File(tmp, "5").list()!!.toList())
  }

  @Test
  fun convertFallbackConvertsAfterMp3Download() = await {
    downloader.m4bAvailable = false
    val r = service(info(BookFormat.M4B_CONVERT_FALLBACK)).enqueue(book("3"), AttemptTrigger.SCHEDULED)
    assertEquals(AttemptStatus.SUCCEEDED, r.result.await())
    assertEquals(DownloadedFormat.M4B_CONVERTED, attempts.get(r.attemptId)!!.format)
    assertEquals(listOf("3"), converter.converted.toList())
  }

  @Test
  fun formatOverrideBeatsServerDefault() = await {
    val service = service(info(BookFormat.M4B_MP3_FALLBACK))
    val r = service.enqueue(book("4"), AttemptTrigger.MANUAL, BookFormat.MP3)
    assertEquals(AttemptStatus.SUCCEEDED, r.result.await())
    assertEquals(DownloadedFormat.MP3, attempts.get(r.attemptId)!!.format)
    assertEquals(BookFormat.MP3, attempts.get(r.attemptId)!!.requestedFormat)
    assertFalse(downloader.calls.any { it.startsWith("m4b") }, "M4B should not be attempted")
  }

  @Test
  fun failureIsRecordedWithoutThrowingAndDoesNotAffectOthers() = await {
    downloader.m4bAvailable = false
    val service = service()
    // book "bad" fails in the MP3 path, "good" is fetched as M4B
    downloader.failMp3With = java.io.IOException("Connection reset", RuntimeException("socket closed"))
    val bad = service.enqueue(book("bad"), AttemptTrigger.SCHEDULED)
    assertEquals(AttemptStatus.FAILED, bad.result.await())
    downloader.m4bAvailable = true
    val good = service.enqueue(book("good"), AttemptTrigger.SCHEDULED)
    assertEquals(AttemptStatus.SUCCEEDED, good.result.await())

    val failed = attempts.get(bad.attemptId)!!
    assertEquals(AttemptStatus.FAILED, failed.status)
    assertTrue(failed.error!!.contains("IOException: Connection reset"), failed.error)
    assertTrue(failed.error!!.contains("socket closed"), "cause is included: ${failed.error}")
    assertEquals(listOf("good"), writer.writes.filterIsInstance<DownloadItem>().map { it.isbn })
    assertFalse(service.isActive("bad"), "a failed book can be queued again")
  }

  @Test
  fun enqueueingAnActiveBookReturnsTheExistingAttempt() = await {
    val gate = CompletableDeferred<Unit>().also { downloader.gate = it }
    val service = service()
    val first = service.enqueue(book("5"), AttemptTrigger.SCHEDULED)
    val second = service.enqueue(book("5"), AttemptTrigger.MANUAL)
    assertFalse(first.alreadyActive)
    assertTrue(second.alreadyActive)
    assertEquals(first.attemptId, second.attemptId)
    assertEquals(1, attempts.rows.size)
    gate.complete(Unit)
    assertEquals(AttemptStatus.SUCCEEDED, second.result.await())
    // once finished it can be queued again
    downloader.gate = null
    val third = service.enqueue(book("5"), AttemptTrigger.MANUAL)
    assertNotEquals(first.attemptId, third.attemptId)
    assertEquals(AttemptStatus.SUCCEEDED, third.result.await())
  }

  @Test
  fun parallelismIsLimitedBySemaphore() = await {
    val gate = CompletableDeferred<Unit>().also { downloader.gate = it }
    val service = service(parallel = 2)
    val results = (1..5).map { service.enqueue(book("p$it"), AttemptTrigger.SCHEDULED) }
    // wait until two are running
    while (downloader.concurrent.get() < 2) delay(5)
    delay(100)
    assertEquals(2, downloader.concurrent.get())
    assertEquals(2, attempts.rows.count { it.status == AttemptStatus.RUNNING })
    assertEquals(3, attempts.rows.count { it.status == AttemptStatus.QUEUED })
    gate.complete(Unit)
    assertTrue(results.map { it.result }.awaitAll().all { it == AttemptStatus.SUCCEEDED })
    assertEquals(2, downloader.maxConcurrent.get())
  }

  @Test
  fun cancellingARunningDownloadMarksItCancelledAndFreesTheBook() = await {
    downloader.gate = CompletableDeferred()
    val service = service()
    val r = service.enqueue(book("6"), AttemptTrigger.MANUAL)
    while (downloader.concurrent.get() < 1) delay(5)
    assertEquals("Downloading M4B", service.currentStep(r.attemptId))

    assertTrue(service.cancel(r.attemptId))
    assertEquals(AttemptStatus.CANCELLED, r.result.await())
    assertEquals(AttemptStatus.CANCELLED, attempts.get(r.attemptId)!!.status)
    assertTrue(writer.writes.filterIsInstance<DownloadItem>().isEmpty(), "nothing is recorded as downloaded")
    assertFalse(service.isActive("6"))
    assertNull(service.currentStep(r.attemptId))
    assertFalse(service.cancel(r.attemptId), "cancelling a finished attempt reports false")
  }

  @Test
  fun cancellingAQueuedDownloadNeverStartsIt() = await {
    val gate = CompletableDeferred<Unit>().also { downloader.gate = it }
    val service = service(parallel = 1)
    val running = service.enqueue(book("7"), AttemptTrigger.SCHEDULED)
    while (downloader.concurrent.get() < 1) delay(5)
    val waiting = service.enqueue(book("8"), AttemptTrigger.SCHEDULED)
    assertEquals(AttemptStatus.QUEUED, attempts.get(waiting.attemptId)!!.status)

    assertTrue(service.cancel(waiting.attemptId))
    assertEquals(AttemptStatus.CANCELLED, waiting.result.await())
    assertNull(attempts.get(waiting.attemptId)!!.startedAt)
    gate.complete(Unit)
    assertEquals(AttemptStatus.SUCCEEDED, running.result.await())
    assertFalse(downloader.calls.contains("m4bmeta:8"))
  }

  @Test
  fun cancellingBeforeTheDownloadIsEvenDispatchedStillFinishesTheAttempt() = await {
    // A single worker kept busy, so the download can't start before it is cancelled.
    val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    val busy = java.util.concurrent.CountDownLatch(1)
    worker.execute { busy.await() }
    val workerScope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())
    try {
      val service = service(processingScope = workerScope)
      val r = service.enqueue(book("14"), AttemptTrigger.MANUAL)
      assertTrue(service.cancel(r.attemptId))
      busy.countDown()

      assertEquals(AttemptStatus.CANCELLED, r.result.await())
      assertEquals(AttemptStatus.CANCELLED, attempts.get(r.attemptId)!!.status)
      assertFalse(service.isActive("14"), "the book can be queued again")
      assertFalse(downloader.calls.any { it.endsWith(":14") })
    } finally {
      workerScope.cancel()
      worker.shutdownNow()
    }
  }

  @Test
  fun manualDownloadsFetchExtrasButScheduledOnesLeaveThemToTheSync() = await {
    val service = service(info(extras = true))
    val manual = service.enqueue(book("9"), AttemptTrigger.MANUAL)
    manual.result.await()
    assertTrue("extras" in downloader.calls)
    assertEquals(listOf("9"), writer.writes.filterIsInstance<DownloadPdfExtraItem>().map { it.isbn })

    downloader.calls.clear()
    service.enqueue(book("10"), AttemptTrigger.SCHEDULED).result.await()
    assertFalse("extras" in downloader.calls)
  }

  @Test
  fun extrasFailureDoesNotFailTheBook() = await {
    downloader.failExtras = true
    val service = service(info(extras = true))
    val r = service.enqueue(book("11"), AttemptTrigger.MANUAL)
    assertEquals(AttemptStatus.SUCCEEDED, r.result.await())
    val attempt = attempts.get(r.attemptId)!!
    assertTrue(attempt.error!!.contains("extras failed"), attempt.error)
    assertEquals(1, writer.writes.filterIsInstance<DownloadItem>().size)
  }

  @Test
  fun downloadedBooksAreImported() = await {
    val r = service().enqueue(book("12"), AttemptTrigger.MANUAL)
    assertEquals(AttemptStatus.SUCCEEDED, r.result.await())
    assertEquals(listOf("12"), importer.imported)
  }

  @Test
  fun anImportFailureDoesNotFailTheBook() = await {
    importer.failWith = java.io.IOException("Audiobookshelf answered HTTP 502")
    val r = service().enqueue(book("13"), AttemptTrigger.MANUAL)
    assertEquals(AttemptStatus.SUCCEEDED, r.result.await())
    val error = attempts.get(r.attemptId)!!.error!!
    assertTrue(error.startsWith("Book downloaded, but adding it to Audiobookshelf failed"), error)
    assertEquals(1, writer.writes.filterIsInstance<DownloadItem>().size)
  }

  @Test
  fun interruptedAttemptsAreRecoveredOnStartup() = await {
    service().recoverInterrupted()
    assertEquals(1, attempts.interrupted.get())
  }

  @Test
  fun errorDescriptionsAreBoundedAndInformative() {
    assertEquals("IllegalStateException", IllegalStateException().describe())
    assertEquals("IllegalStateException: nope", IllegalStateException("nope").describe())
    assertTrue(IllegalStateException("x".repeat(5000)).describe().length <= 2001)
    assertTrue(RuntimeException("outer", IllegalArgumentException("inner")).describe().endsWith("(caused by IllegalArgumentException: inner)"))
  }
}
