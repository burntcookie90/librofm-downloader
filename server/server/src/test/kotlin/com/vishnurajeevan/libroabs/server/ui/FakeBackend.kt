package com.vishnurajeevan.libroabs.server.ui

import com.vishnurajeevan.libroabs.db.repo.AttemptPage
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.BookInfo
import com.vishnurajeevan.libroabs.models.libro.Genre
import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.AttemptStatus
import com.vishnurajeevan.libroabs.models.server.AttemptTrigger
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.DownloadAttempt
import com.vishnurajeevan.libroabs.models.server.DownloadedFormat
import com.vishnurajeevan.libroabs.models.server.ItemDownloadHistory
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.SyncSnapshot
import com.vishnurajeevan.libroabs.models.server.SyncTrigger
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

fun testServerInfo(password: String? = null) = ServerInfo(
  libroUserName = "reader@example.com",
  libroPassword = "hunter2",
  port = 8080,
  dataDir = "/data",
  mediaDir = "/media",
  syncInterval = "d",
  parallelCount = 2,
  dryRun = false,
  renameChapters = true,
  writeTitleTag = false,
  format = BookFormat.M4B_MP3_FALLBACK,
  downloadExtras = true,
  logLevel = ApplicationLogLevel.NONE,
  limit = -1,
  pathPattern = "FIRST_AUTHOR/BOOK_TITLE",
  healthCheckHost = "https://hc-ping.com",
  healthCheckId = "abc",
  trackerToken = "secret-token",
  trackerEndpoint = "https://api.hardcover.app/v1/graphql",
  ffmpegPath = "/usr/bin/ffmpeg",
  ffprobePath = "/usr/bin/ffprobe",
  audioQuality = "128k",
  skipTrackingIsbns = emptyList(),
  hardcoverSyncMode = TrackerSyncMode.ALL,
  webhookUrls = listOf("https://hooks.example.com/secret/path"),
  webUiPassword = password,
)

private fun cover(seed: Int): String {
  val hues = listOf(168, 210, 28, 340, 262, 48, 190, 0)
  val h = hues[seed % hues.size]
  val svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 10 10'><rect width='10' height='10' fill='hsl($h,45%,38%)'/>" +
    "<rect x='1.5' y='1.5' width='7' height='7' fill='none' stroke='hsl($h,60%,80%)' stroke-width='.3'/></svg>"
  return "data:image/svg+xml," + svg.replace("<", "%3C").replace(">", "%3E").replace("#", "%23").replace("'", "%27").replace(" ", "%20")
}

fun sampleBook(i: Int, title: String = "Book number $i", authors: List<String> = listOf("Author ${i % 7}")) = Book(
  title = title,
  authors = authors,
  isbn = (9780000000000L + i).toString(),
  cover_url = cover(i),
  audiobook_info = BookInfo(
    narrators = listOf("Narrator ${i % 5}"),
    duration = 3600 * (2 + i % 14) + 60 * (i % 60),
    track_count = 10 + i % 30,
  ),
  publisher = "Example Audio",
  publication_date = Instant.parse("20${10 + i % 15}-0${1 + i % 9}-1${i % 9}T00:00:00Z"),
  description = "<p>A description of book $i.</p><p>It has <b>two</b> paragraphs &amp; entities.</p>",
  genres = listOf(Genre("Fiction"), Genre("Fantasy")),
  series = if (i % 4 == 0) "Series ${i % 3}" else null,
  series_num = if (i % 4 == 0) i % 5 + 1 else null,
)

/**
 * In-memory backend. Downloads are simulated with short delays; every third one fails.
 */
class FakeBackend(
  override val serverInfo: ServerInfo = testServerInfo(),
  private val scope: CoroutineScope,
  bookCount: Int = 40,
  private val stepDelayMs: Long = 600,
) : WebUiBackend {
  val books = CopyOnWriteArrayList<Book>().apply {
    addAll(
      (1..bookCount).map {
        sampleBook(
          it,
          title = listOf("The Long Way Home", "Echoes of Tomorrow", "A Study in Dust", "Hollow Crown", "Night Library")[it % 5] + " $it",
        )
      }
    )
  }
  private val history = ConcurrentHashMap<String, ItemDownloadHistory>()
  val attempts = CopyOnWriteArrayList<DownloadAttempt>()
  private val ids = AtomicLong(0)
  private val steps = ConcurrentHashMap<Long, String>()
  private val jobs = ConcurrentHashMap<Long, Job>()
  @Volatile var sync = SyncSnapshot()
  val enqueued = CopyOnWriteArrayList<String>()
  private var counter = 0

  init {
    // Seed a few downloaded books and some history.
    books.take(bookCount / 2).forEach { b ->
      history[b.isbn] = ItemDownloadHistory(b.isbn, DownloadedFormat.M4B, "/media/${b.authors.first()}/${b.title}", hasPdfDownloaded = true)
      attempts += DownloadAttempt(ids.incrementAndGet(), b.isbn, b.title, AttemptTrigger.SCHEDULED, AttemptStatus.SUCCEEDED, null,
        DownloadedFormat.M4B, null, System.currentTimeMillis() - 86_400_000, System.currentTimeMillis() - 86_390_000, System.currentTimeMillis() - 86_300_000)
    }
    books.drop(bookCount / 2).take(3).forEach { b ->
      attempts += DownloadAttempt(ids.incrementAndGet(), b.isbn, b.title, AttemptTrigger.SCHEDULED, AttemptStatus.FAILED,
        "IOException: Connection reset while downloading part 3 of 7 (caused by SocketException: Connection reset)",
        null, null, System.currentTimeMillis() - 3_600_000, System.currentTimeMillis() - 3_599_000, System.currentTimeMillis() - 3_500_000)
    }
  }

  private fun latest() = attempts.groupBy { it.isbn }.mapValues { (_, v) -> v.maxBy { it.id } }

  private fun entry(book: Book, latest: Map<String, DownloadAttempt> = latest()): LibraryEntry {
    val l = latest[book.isbn]
    return LibraryEntry(book, history[book.isbn], l, l?.takeIf { it.status.isActive }?.let { steps[it.id] })
  }

  override suspend fun libraryEntries(): List<LibraryEntry> = latest().let { l -> books.map { entry(it, l) } }
  override suspend fun libraryEntry(isbn: String): LibraryEntry? = books.firstOrNull { it.isbn == isbn }?.let { entry(it) }
  override suspend fun stats(): DashboardStats {
    val e = libraryEntries()
    return DashboardStats(e.size, e.count { it.state == BookState.DOWNLOADED }, e.count { it.state == BookState.NOT_DOWNLOADED },
      e.count { it.state == BookState.FAILED }, e.count { it.state.isActive })
  }

  override suspend fun attempt(attemptId: Long) = attempts.firstOrNull { it.id == attemptId }
  override suspend fun attemptsFor(isbn: String) = attempts.filter { it.isbn == isbn }.sortedByDescending { it.id }
  override suspend fun recentAttempts(limit: Int) = attempts.sortedByDescending { it.id }.take(limit)
  override suspend fun activeAttempts() = attempts.filter { it.status.isActive }.map { ActiveAttempt(it, steps[it.id]) }
  override suspend fun attemptPage(status: AttemptStatus?, query: String?, page: Int, pageSize: Int): AttemptPage {
    val filtered = attempts.sortedByDescending { it.id }.filter {
      (status == null || it.status == status) &&
        (query.isNullOrBlank() || it.title.contains(query, true) || it.isbn.contains(query, true))
    }
    return AttemptPage(filtered.drop((page - 1) * pageSize).take(pageSize), filtered.size.toLong())
  }

  override suspend fun attemptCounts() = attempts.groupingBy { it.status }.eachCount().mapValues { it.value.toLong() }

  private fun update(id: Long, f: (DownloadAttempt) -> DownloadAttempt) {
    val idx = attempts.indexOfFirst { it.id == id }
    if (idx >= 0) attempts[idx] = f(attempts[idx])
  }

  private fun start(book: Book, trigger: AttemptTrigger, format: BookFormat?): EnqueueOutcome {
    attempts.firstOrNull { it.isbn == book.isbn && it.status.isActive }?.let { return EnqueueOutcome.AlreadyActive(it.id) }
    enqueued += book.isbn
    val id = ids.incrementAndGet()
    val shouldFail = ++counter % 3 == 0
    attempts += DownloadAttempt(id, book.isbn, book.title, trigger, AttemptStatus.QUEUED, null, null, format, System.currentTimeMillis(), null, null)
    jobs[id] = scope.launch {
      try {
        delay(stepDelayMs)
        update(id) { it.copy(status = AttemptStatus.RUNNING, startedAt = System.currentTimeMillis()) }
        steps[id] = "Downloading M4B"
        delay(stepDelayMs * 2)
        if (shouldFail) {
          update(id) { it.copy(status = AttemptStatus.FAILED, error = "IllegalStateException: libro.fm returned HTTP 503 for ${book.title}", finishedAt = System.currentTimeMillis()) }
        } else {
          history[book.isbn] = ItemDownloadHistory(book.isbn, DownloadedFormat.M4B, "/media/${book.title}", false)
          update(id) { it.copy(status = AttemptStatus.SUCCEEDED, format = DownloadedFormat.M4B, finishedAt = System.currentTimeMillis()) }
        }
      } catch (e: kotlinx.coroutines.CancellationException) {
        update(id) { it.copy(status = AttemptStatus.CANCELLED, error = "Cancelled", finishedAt = System.currentTimeMillis()) }
      } finally {
        steps.remove(id)
        jobs.remove(id)
      }
    }
    return EnqueueOutcome.Queued(id)
  }

  override suspend fun enqueueDownload(isbn: String, format: BookFormat?): EnqueueOutcome {
    val book = books.firstOrNull { it.isbn == isbn } ?: return EnqueueOutcome.Failure("Could not find $isbn on libro.fm")
    return start(book, AttemptTrigger.MANUAL, format)
  }

  override suspend fun enqueueDownloads(isbns: List<String>, format: BookFormat?) =
    isbns.distinct().mapNotNull { i -> books.firstOrNull { it.isbn == i } }.count { start(it, AttemptTrigger.MANUAL, format) is EnqueueOutcome.Queued }

  override suspend fun downloadMissing() = libraryEntries()
    .filter { it.state == BookState.NOT_DOWNLOADED || it.state == BookState.FAILED }
    .count { start(it.book, AttemptTrigger.MANUAL, null) is EnqueueOutcome.Queued }

  override suspend fun retryAttempt(attemptId: Long): EnqueueOutcome {
    val a = attempt(attemptId) ?: return EnqueueOutcome.NotFound
    val book = books.firstOrNull { it.isbn == a.isbn } ?: return EnqueueOutcome.NotFound
    return start(book, AttemptTrigger.RETRY, a.requestedFormat)
  }

  override suspend fun retryFailed() = libraryEntries().filter { it.state == BookState.FAILED }
    .count { start(it.book, AttemptTrigger.RETRY, null) is EnqueueOutcome.Queued }

  override suspend fun cancelAttempt(attemptId: Long): Boolean {
    val job = jobs[attemptId] ?: return false
    job.cancel()
    job.join()
    return true
  }

  override suspend fun forgetDownload(isbn: String) {
    history.remove(isbn)
  }

  override suspend fun deleteAttempt(attemptId: Long) = attempts.removeIf { it.id == attemptId && !it.status.isActive }
  override suspend fun clearAttempts(olderThanDays: Int?): Int {
    val before = attempts.size
    attempts.removeIf { !it.status.isActive }
    return before - attempts.size
  }

  override fun syncSnapshot() = sync
  override fun requestSync(overwrite: Boolean): Boolean {
    if (sync.running) return false
    sync = sync.copy(running = true, trigger = SyncTrigger.MANUAL, overwrite = overwrite, startedAt = System.currentTimeMillis())
    scope.launch {
      delay(stepDelayMs * 4)
      sync = SyncSnapshot(lastFinishedAt = System.currentTimeMillis(), lastDurationMs = stepDelayMs * 4, lastTrigger = SyncTrigger.MANUAL)
    }
    return true
  }

  override suspend fun wishlist() = WishlistOverview(
    libroSynced = mapOf("9781111111111" to true, "9782222222222" to false),
    trackerEnabled = true,
    trackerSynced = mapOf("9783333333333" to true),
  )

  // ---- audiobookshelf ----
  @Volatile var absEnabled = false
  @Volatile var absPlans: (String) -> AbsPlanResult = { AbsPlanResult.NotInAudiobookshelf }
  @Volatile var absOverviewResult = AbsSyncOverview(emptyList(), 0, emptyList())
  @Volatile var absApplyError: String? = null
  data class AbsApplied(val isbn: String, val itemId: String, val fields: Set<String>?, val cover: Boolean)
  val absApplied = CopyOnWriteArrayList<AbsApplied>()
  override val absFields = listOf("NARRATORS" to "Narrators", "SERIES" to "Series", "CHAPTERS" to "Chapter names")

  override val audiobookshelfEnabled: Boolean get() = absEnabled
  override suspend fun absPlan(isbn: String) = absPlans(isbn)
  override suspend fun absPlanAll() = absOverviewResult
  override suspend fun absApply(isbn: String, itemId: String, fields: Set<String>?, replaceCover: Boolean): String? {
    absApplied += AbsApplied(isbn, itemId, fields, replaceCover)
    return absApplyError
  }
  override suspend fun absCover(itemId: String) = if (itemId == "item-1") "jpeg".toByteArray() to "image/jpeg" else null
}
