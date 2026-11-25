package com.vishnurajeevan.libroabs.tracker

import com.vishnurajeevan.libroabs.audiobookshelf.ListeningProgress
import com.vishnurajeevan.libroabs.audiobookshelf.ListeningProgressSource
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.EditionExtras
import com.vishnurajeevan.libroabs.connector.ReadingStatus
import com.vishnurajeevan.libroabs.connector.TrackerCandidate
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.connector.TrackerRead
import com.vishnurajeevan.libroabs.connector.TrackerUserBook
import com.vishnurajeevan.libroabs.db.repo.SyncStateRepo
import com.vishnurajeevan.libroabs.download.FakeWishlist
import com.vishnurajeevan.libroabs.download.FakeWriter
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.libro.AudioBook
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import java.io.File
import kotlin.time.Clock
import kotlin.time.Instant

/** A Hardcover that keeps its shelves in memory and records every change asked of it. */
internal class FakeTracker : TrackerConnector {
  val calls = mutableListOf<String>()
  var wanted = listOf<ConnectorBook>()
  var owned = listOf<ConnectorBook>()
  var read = listOf<ConnectorBook>()
  var editions = listOf<ConnectorBook>()
  var search: (String) -> List<TrackerCandidate> = { emptyList() }
  val userBooks = mutableMapOf<String, TrackerUserBook>()
  var created: Pair<ConnectorBook, EditionExtras>? = null
  var failMarkOwned: (ConnectorBook) -> Exception? = { null }
  var failCreateEdition: Exception? = null

  /** A read Hardcover starts itself when a status is set, as the website does. */
  var startsReadOnStatus = false

  override suspend fun login() {}
  override suspend fun getWantedBooks() = wanted
  override suspend fun getOwnedBooks() = owned
  override suspend fun getReadBooks() = read
  override suspend fun getEditions(isbn13s: List<String>) =
    editions.filter { book -> book.connectorAudioBook.any { it.isbn13 in isbn13s } }

  override suspend fun searchBooks(query: String) = search(query).also { calls += "search $query" }

  override suspend fun createEdition(book: ConnectorBook, extras: EditionExtras): ConnectorBook {
    calls += "createEdition ${book.id}"
    failCreateEdition?.let { throw it }
    created = book to extras
    return book.copy(connectorAudioBook = listOf(book.connectorAudioBook.first().copy(id = "new-edition")))
  }

  override suspend fun markWanted(book: ConnectorBook) {
    calls += "markWanted ${book.id}/${book.connectorAudioBook.firstOrNull()?.id}"
  }

  override suspend fun markOwned(book: ConnectorBook) {
    failMarkOwned(book)?.let { throw it }
    calls += "markOwned ${book.id}/${book.connectorAudioBook.first().id}"
  }

  override suspend fun getUserBooks(bookIds: List<String>) = userBooks.values.filter { it.bookId in bookIds }

  override suspend fun setStatus(bookId: String, editionId: String, status: ReadingStatus, userBookId: String?): String {
    calls += "setStatus $bookId $editionId $status"
    val id = userBookId ?: "ub-$bookId"
    val existing = userBooks[bookId]
    val reads = existing?.reads.orEmpty().let { reads ->
      if (startsReadOnStatus) listOf(TrackerRead("auto", null, null, null, editionId)) + reads else reads
    }
    userBooks[bookId] = TrackerUserBook(id, bookId, status, editionId, reads)
    return id
  }

  override suspend fun saveRead(userBookId: String, read: TrackerRead) {
    calls += "saveRead $userBookId $read"
  }
}

internal class FakeShelves : LibroShelves {
  var library = listOf<Book>()
  var wishlist = listOf<AudioBook>()
  var addedFromTracker = setOf<String>()
  val addedToWishlist = mutableListOf<String>()
  var details: (String) -> Book = { error("unknown book $it") }

  override suspend fun library() = library
  override suspend fun wishlist() = wishlist
  override suspend fun bookDetails(isbn: String) = details(isbn)
  override suspend fun addedFromTracker() = addedFromTracker
  override suspend fun addToWishlist(isbns: List<String>) {
    addedToWishlist += isbns
  }

  override suspend fun downloadDirs(): Map<String, File> = emptyMap()
}

internal class FakeProgress(var available: Boolean = true, var progress: Map<String, ListeningProgress> = emptyMap()) :
  ListeningProgressSource {
  override val progressAvailable: Boolean get() = available
  override suspend fun progress(books: List<Pair<Book, File?>>) = progress.filterKeys { isbn -> books.any { it.first.isbn == isbn } }
}

internal class FakeSyncState : SyncStateRepo {
  val values = mutableMapOf<String, String>()
  override suspend fun get(key: String) = values[key]
  override suspend fun put(key: String, value: String) {
    values[key] = value
  }
}

internal class FixedClock(var now: Instant) : Clock {
  override fun now() = now
}

internal val silentLogger = object : Logger {
  override fun v(msg: String) {}
  override fun i(msg: String) {}
}

internal fun trackerInfo(
  mode: TrackerSyncMode = TrackerSyncMode.LIBRO_OWNED_TO_HARDCOVER,
  dryRun: Boolean = false,
  syncProgress: Boolean = false,
  skip: List<String> = emptyList(),
) = ServerInfo(
  libroUserName = "u", libroPassword = "p", port = 0, dataDir = "", mediaDir = "", syncInterval = "d", parallelCount = 1,
  dryRun = dryRun, renameChapters = false, writeTitleTag = false, format = BookFormat.M4B_MP3_FALLBACK, downloadExtras = false,
  logLevel = ApplicationLogLevel.NONE, limit = -1, pathPattern = "", healthCheckHost = "", healthCheckId = null, trackerToken = "t",
  trackerEndpoint = "", ffmpegPath = "", ffprobePath = "", audioQuality = "128k", skipTrackingIsbns = skip,
  hardcoverSyncMode = mode, hardcoverSyncProgress = syncProgress,
)

/** A [HardcoverSync] with Hardcover turned off. */
internal fun disabledHardcoverSync() = HardcoverSync(
  serverInfo = trackerInfo(),
  trackerConnector = null,
  libro = FakeShelves(),
  progressSource = FakeProgress(available = false),
  trackerWishlistRepo = FakeWishlist(),
  dbWriter = FakeWriter(),
  syncState = FakeSyncState(),
  logger = silentLogger,
)
