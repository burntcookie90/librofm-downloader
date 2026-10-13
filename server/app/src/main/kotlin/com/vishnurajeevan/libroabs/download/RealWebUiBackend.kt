package com.vishnurajeevan.libroabs.download

import com.vishnurajeevan.libroabs.audiobookshelf.AbsMetadataSync
import com.vishnurajeevan.libroabs.audiobookshelf.absFieldChoices
import com.vishnurajeevan.libroabs.server.ui.AbsPlanResult
import com.vishnurajeevan.libroabs.server.ui.AbsSyncOverview
import java.io.File
import com.vishnurajeevan.libroabs.db.repo.AttemptPage
import com.vishnurajeevan.libroabs.db.repo.DownloadAttemptRepo
import com.vishnurajeevan.libroabs.db.repo.DownloadHistoryRepo
import com.vishnurajeevan.libroabs.db.repo.LibroFmWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.repo.TrackerWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.DeleteDownloadHistoryItem
import com.vishnurajeevan.libroabs.libro.LibroFmBooks
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.server.AttemptStatus
import com.vishnurajeevan.libroabs.models.server.AttemptTrigger
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.DownloadAttempt
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.SyncSnapshot
import com.vishnurajeevan.libroabs.models.server.SyncTrigger
import com.vishnurajeevan.libroabs.server.ui.ActiveAttempt
import com.vishnurajeevan.libroabs.server.ui.BookState
import com.vishnurajeevan.libroabs.server.ui.DashboardStats
import com.vishnurajeevan.libroabs.server.ui.EnqueueOutcome
import com.vishnurajeevan.libroabs.server.ui.LibraryEntry
import com.vishnurajeevan.libroabs.server.ui.WebUiBackend
import com.vishnurajeevan.libroabs.server.ui.WishlistOverview
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class RealWebUiBackend(
  override val serverInfo: ServerInfo,
  private val libroClient: LibroFmBooks,
  private val downloadService: DownloadService,
  private val syncController: SyncController,
  private val attemptRepo: DownloadAttemptRepo,
  private val downloadHistoryRepo: DownloadHistoryRepo,
  private val dbWriter: DbWriter,
  private val libroWishlistRepo: LibroFmWishlistSyncStatusRepo,
  private val trackerWishlistRepo: TrackerWishlistSyncStatusRepo,
  private val absMetadataSync: AbsMetadataSync,
) : WebUiBackend {

  override val audiobookshelfEnabled: Boolean get() = serverInfo.audiobookshelf != null

  /** Where this app saved each book it downloaded. */
  private suspend fun downloadDirs(): Map<String, File> =
    downloadHistoryRepo.downloadHistory().associate { it.isbn to File(it.path) }

  override suspend fun absPlan(isbn: String): AbsPlanResult {
    val book = libroClient.getLocalLibrary().audiobooks.firstOrNull { it.isbn == isbn }
      ?: return AbsPlanResult.Failure("Book not found")
    return try {
      absMetadataSync.plan(book, downloadDirs()[isbn])
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      AbsPlanResult.Failure(e.describe())
    }
  }

  override suspend fun absPlanAll(): AbsSyncOverview {
    val dirs = downloadDirs()
    return absMetadataSync.planAll(libroClient.getLocalLibrary().audiobooks.map { it to dirs[it.isbn] })
  }

  override val absFields: List<Pair<String, String>> get() = absFieldChoices

  override suspend fun absApply(isbn: String, itemId: String, fields: Set<String>?, replaceCover: Boolean): String? {
    val book = libroClient.getLocalLibrary().audiobooks.firstOrNull { it.isbn == isbn } ?: return "Book not found"
    return try {
      absMetadataSync.apply(book, downloadDirs()[isbn], itemId, fields, replaceCover)
      null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      e.describe()
    }
  }

  override suspend fun absCover(itemId: String): Pair<ByteArray, String>? = try {
    absMetadataSync.cover(itemId)
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }

  override suspend fun libraryEntries(): List<LibraryEntry> {
    val books = libroClient.getLocalLibrary().audiobooks
    val history = downloadHistoryRepo.downloadHistory().associateBy { it.isbn }
    val latest = attemptRepo.latestPerIsbn()
    return books.map { book -> entryFor(book, history[book.isbn], latest[book.isbn]) }
  }

  override suspend fun libraryEntry(isbn: String): LibraryEntry? {
    val book = libroClient.getLocalLibrary().audiobooks.firstOrNull { it.isbn == isbn } ?: return null
    val history = downloadHistoryRepo.downloadHistory().firstOrNull { it.isbn == isbn }
    val latest = attemptRepo.forIsbn(isbn, 1).firstOrNull()
    return entryFor(book, history, latest)
  }

  private fun entryFor(
    book: Book,
    history: com.vishnurajeevan.libroabs.models.server.ItemDownloadHistory?,
    latest: DownloadAttempt?,
  ) = LibraryEntry(
    book = book,
    download = history,
    latestAttempt = latest,
    step = latest?.takeIf { it.status.isActive }?.let { downloadService.currentStep(it.id) },
  )

  override suspend fun stats(): DashboardStats {
    val entries = libraryEntries()
    return DashboardStats(
      totalBooks = entries.size,
      downloaded = entries.count { it.state == BookState.DOWNLOADED },
      notDownloaded = entries.count { it.state == BookState.NOT_DOWNLOADED },
      failed = entries.count { it.state == BookState.FAILED },
      active = entries.count { it.state.isActive },
    )
  }

  override suspend fun attempt(attemptId: Long): DownloadAttempt? = attemptRepo.get(attemptId)

  override suspend fun attemptsFor(isbn: String): List<DownloadAttempt> = attemptRepo.forIsbn(isbn, 25)

  override suspend fun recentAttempts(limit: Int): List<DownloadAttempt> = attemptRepo.recent(limit)

  override suspend fun activeAttempts(): List<ActiveAttempt> =
    attemptRepo.active().map { ActiveAttempt(it, downloadService.currentStep(it.id)) }

  override suspend fun attemptPage(
    status: AttemptStatus?,
    query: String?,
    page: Int,
    pageSize: Int,
  ): AttemptPage = attemptRepo.page(status, query, pageSize, (page - 1).coerceAtLeast(0) * pageSize)

  override suspend fun attemptCounts(): Map<AttemptStatus, Long> = attemptRepo.statusCounts()

  private suspend fun findBook(isbn: String): Book? =
    libroClient.getLocalLibrary().audiobooks.firstOrNull { it.isbn == isbn }

  override suspend fun enqueueDownload(isbn: String, format: BookFormat?): EnqueueOutcome =
    enqueueIsbn(isbn, AttemptTrigger.MANUAL, format)

  private suspend fun enqueueIsbn(isbn: String, trigger: AttemptTrigger, format: BookFormat?): EnqueueOutcome {
    val book = findBook(isbn) ?: try {
      libroClient.fetchBookDetails(isbn)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      return EnqueueOutcome.Failure("Could not find $isbn on libro.fm: ${e.describe()}")
    }
    return enqueueBook(book, trigger, format)
  }

  private suspend fun enqueueBook(book: Book, trigger: AttemptTrigger, format: BookFormat?): EnqueueOutcome {
    val result = downloadService.enqueue(book, trigger, format)
    return if (result.alreadyActive) EnqueueOutcome.AlreadyActive(result.attemptId) else EnqueueOutcome.Queued(result.attemptId)
  }

  override suspend fun enqueueDownloads(isbns: List<String>, format: BookFormat?): Int {
    val byIsbn = libroClient.getLocalLibrary().audiobooks.associateBy { it.isbn }
    return isbns.distinct().mapNotNull { byIsbn[it] }.count {
      enqueueBook(it, AttemptTrigger.MANUAL, format) is EnqueueOutcome.Queued
    }
  }

  override suspend fun downloadMissing(): Int = libraryEntries()
    .filter { it.state == BookState.NOT_DOWNLOADED || it.state == BookState.FAILED }
    .count { enqueueBook(it.book, AttemptTrigger.MANUAL, null) is EnqueueOutcome.Queued }

  override suspend fun retryAttempt(attemptId: Long): EnqueueOutcome {
    val attempt = attemptRepo.get(attemptId) ?: return EnqueueOutcome.NotFound
    return enqueueIsbn(attempt.isbn, AttemptTrigger.RETRY, attempt.requestedFormat)
  }

  override suspend fun retryFailed(): Int = libraryEntries()
    .filter { it.state == BookState.FAILED }
    .count {
      enqueueBook(it.book, AttemptTrigger.RETRY, it.latestAttempt?.requestedFormat) is EnqueueOutcome.Queued
    }

  override suspend fun cancelAttempt(attemptId: Long): Boolean = downloadService.cancel(attemptId)

  override suspend fun forgetDownload(isbn: String) {
    dbWriter.write(DeleteDownloadHistoryItem(isbn))
  }

  override suspend fun deleteAttempt(attemptId: Long): Boolean = attemptRepo.deleteFinished(attemptId)

  override suspend fun clearAttempts(olderThanDays: Int?): Int =
    if (olderThanDays == null) {
      attemptRepo.deleteFinished()
    } else {
      attemptRepo.deleteFinishedBefore(System.currentTimeMillis() - olderThanDays * MILLIS_PER_DAY)
    }

  override fun syncSnapshot(): SyncSnapshot = syncController.state.value

  override fun requestSync(overwrite: Boolean): Boolean = syncController.requestSync(SyncTrigger.MANUAL, overwrite)

  override suspend fun wishlist(): WishlistOverview = WishlistOverview(
    libroSynced = libroWishlistRepo.allStatuses(),
    trackerEnabled = !serverInfo.trackerToken.isNullOrEmpty(),
    trackerSynced = trackerWishlistRepo.allStatuses(),
  )

  private companion object {
    const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
  }
}
