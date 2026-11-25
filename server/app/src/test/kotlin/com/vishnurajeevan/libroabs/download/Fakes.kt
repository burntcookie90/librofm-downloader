package com.vishnurajeevan.libroabs.download

import com.vishnurajeevan.libroabs.converter.ffmpeg.M4bConverter
import com.vishnurajeevan.libroabs.db.repo.AttemptPage
import com.vishnurajeevan.libroabs.db.repo.DownloadAttemptRepo
import com.vishnurajeevan.libroabs.db.repo.DownloadHistoryRepo
import com.vishnurajeevan.libroabs.db.repo.LibroFmWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.repo.TrackerWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWrite
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.DeleteDownloadHistoryItem
import com.vishnurajeevan.libroabs.db.writer.DownloadItem
import com.vishnurajeevan.libroabs.libro.LibroFmBooks
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.BookInfo
import com.vishnurajeevan.libroabs.models.libro.DownloadPart
import com.vishnurajeevan.libroabs.models.libro.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.models.libro.PdfExtra
import com.vishnurajeevan.libroabs.models.libro.Tracks
import com.vishnurajeevan.libroabs.models.server.AttemptStatus
import com.vishnurajeevan.libroabs.models.server.AttemptTrigger
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.DownloadAttempt
import com.vishnurajeevan.libroabs.models.server.DownloadedFormat
import com.vishnurajeevan.libroabs.models.server.ItemDownloadHistory
import com.vishnurajeevan.libroabs.models.server.M4bMetadata
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.datetime.Instant
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

internal fun testBook(isbn: String, title: String = "Book $isbn") = Book(
  title = title, authors = listOf("A"), isbn = isbn, cover_url = "", audiobook_info = BookInfo(emptyList(), 60, 1),
  publisher = "P", publication_date = Instant.parse("2020-01-01T00:00:00Z"), description = "", genres = emptyList(),
)

internal class FakeLibroFm : LibroFmBooks {
  @Volatile var library: List<Book> = emptyList()
  @Volatile var details: (String) -> Book = { error("unknown book $it") }
  val calls = CopyOnWriteArrayList<String>()
  @Volatile var m4bAvailable = true
  @Volatile var failMp3With: Throwable? = null
  @Volatile var failExtras = false
  @Volatile var tracks: List<Tracks> = listOf(Tracks(1, "One"))
  /** Files the fake "extracts" into the book folder when MP3s are downloaded. */
  @Volatile var mp3Names: List<String> = emptyList()
  @Volatile var gate: CompletableDeferred<Unit>? = null
  val concurrent = AtomicInteger(0)
  val maxConcurrent = AtomicInteger(0)

  override suspend fun getLocalLibrary() = LibraryMetadata(audiobooks = library)
  override suspend fun fetchBookDetails(isbn: String): Book = details(isbn)

  override suspend fun fetchMp3DownloadMetadata(isbn: String): Mp3DownloadMetadata {
    calls += "mp3meta:$isbn"
    return Mp3DownloadMetadata(listOf(DownloadPart("https://x/part.zip", 1)), tracks)
  }

  override suspend fun fetchM4bMetadata(isbn: String): Result<M4bMetadata> {
    calls += "m4bmeta:$isbn"
    return if (m4bAvailable) Result.success(M4bMetadata("https://x/book.m4b")) else Result.failure(Exception("M4B Not Found!"))
  }

  override suspend fun downloadM4b(m4bUrl: String, targetDirectory: File) {
    calls += "m4b"
    val now = concurrent.incrementAndGet()
    maxConcurrent.updateAndGet { maxOf(it, now) }
    try {
      gate?.await()
    } finally {
      concurrent.decrementAndGet()
    }
  }

  override suspend fun downloadMp3s(data: List<DownloadPart>, targetDirectory: File): List<File> {
    calls += "mp3s"
    failMp3With?.let { throw it }
    return mp3Names.map { File(targetDirectory, it).apply { writeText(it) } }
  }

  override suspend fun downloadPdfExtras(isbn: String, data: List<PdfExtra>, targetDirectory: File) {
    calls += "extras"
    if (failExtras) throw IllegalStateException("extras exploded")
  }
}

internal class FakeConverter : M4bConverter {
  val converted = CopyOnWriteArrayList<String>()
  override suspend fun convertBookToM4b(book: Book, tracks: List<Tracks>, targetDirectory: File, audioQuality: String) {
    converted += book.isbn
  }
}

/** Records writes and keeps [history] in sync the way the database would. */
internal class FakeWriter(private val history: FakeHistory? = null) : DbWriter {
  val writes = CopyOnWriteArrayList<DbWrite>()
  override suspend fun write(write: DbWrite) {
    writes += write
    when (write) {
      is DownloadItem -> history?.map?.put(write.isbn, ItemDownloadHistory(write.isbn, write.format, write.path, false))
      is DeleteDownloadHistoryItem -> history?.map?.remove(write.isbn)
      else -> {}
    }
  }
}

internal class FakeHistory : DownloadHistoryRepo {
  val map = ConcurrentHashMap<String, ItemDownloadHistory>()
  override suspend fun isDownloaded(isbn: String) = map.containsKey(isbn)
  override suspend fun downloadCount() = map.size.toLong()
  override suspend fun pdfExtrasDownloaded(isbn: String) = map[isbn]?.hasPdfDownloaded == true
  override suspend fun downloadHistory() = map.values.toList()
  override suspend fun downloadHistory(isbn: String) = map.getValue(isbn)
}

internal class FakeWishlist(private val statuses: Map<String, Boolean> = emptyMap()) :
  LibroFmWishlistSyncStatusRepo, TrackerWishlistSyncStatusRepo {
  override suspend fun getSyncedIsbns() = statuses.keys.toList()
  override suspend fun allStatuses() = statuses
}

internal class FakeAttempts : DownloadAttemptRepo {
  val rows = CopyOnWriteArrayList<DownloadAttempt>()
  val interrupted = AtomicInteger(0)

  private fun mutate(id: Long, f: (DownloadAttempt) -> DownloadAttempt) {
    val i = rows.indexOfFirst { it.id == id }
    rows[i] = f(rows[i])
  }

  override suspend fun create(isbn: String, title: String, trigger: AttemptTrigger, requestedFormat: BookFormat?, now: Long): Long {
    val id = rows.size + 1L
    rows += DownloadAttempt(id, isbn, title, trigger, AttemptStatus.QUEUED, null, null, requestedFormat, now, null, null)
    return id
  }

  override suspend fun markRunning(id: Long, now: Long) = mutate(id) { it.copy(status = AttemptStatus.RUNNING, startedAt = now) }
  override suspend fun markFinished(id: Long, status: AttemptStatus, error: String?, format: DownloadedFormat?, now: Long) =
    mutate(id) { it.copy(status = status, error = error, format = format, finishedAt = now) }

  override suspend fun get(id: Long) = rows.firstOrNull { it.id == id }
  override suspend fun active() = rows.filter { it.status.isActive }
  override suspend fun recent(limit: Int) = rows.takeLast(limit).reversed()
  override suspend fun forIsbn(isbn: String, limit: Int) = rows.filter { it.isbn == isbn }.reversed().take(limit)
  override suspend fun page(status: AttemptStatus?, query: String?, limit: Int, offset: Int) =
    rows.filter { status == null || it.status == status }.let { AttemptPage(it.drop(offset).take(limit), it.size.toLong()) }

  override suspend fun statusCounts() = rows.groupingBy { it.status }.eachCount().mapValues { it.value.toLong() }
  override suspend fun latestPerIsbn() = rows.groupBy { it.isbn }.mapValues { it.value.last() }
  override suspend fun interruptActive(reason: String, now: Long): Int = interrupted.incrementAndGet()
  override suspend fun deleteFinished(): Int {
    val n = rows.count { !it.status.isActive }
    rows.removeIf { !it.status.isActive }
    return n
  }

  override suspend fun deleteFinishedBefore(queuedBefore: Long): Int {
    val n = rows.count { !it.status.isActive && it.queuedAt < queuedBefore }
    rows.removeIf { !it.status.isActive && it.queuedAt < queuedBefore }
    return n
  }

  override suspend fun deleteFinished(id: Long) = rows.removeIf { it.id == id && !it.status.isActive }
}

internal class FakeImporter : com.vishnurajeevan.libroabs.audiobookshelf.LibraryImporter {
  override var enabled = true
  @Volatile var failWith: Throwable? = null
  val imported = CopyOnWriteArrayList<String>()

  override suspend fun import(book: Book, directory: File) {
    failWith?.let { throw it }
    imported += book.isbn
  }
}
