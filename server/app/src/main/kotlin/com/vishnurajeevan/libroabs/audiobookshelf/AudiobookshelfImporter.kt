package com.vishnurajeevan.libroabs.audiobookshelf

import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsBookMetadata
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsChapter
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsField
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsLibraryItem
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AudiobookshelfClient
import com.vishnurajeevan.libroabs.connector.audiobookshelf.toBookMetadata
import com.vishnurajeevan.libroabs.libro.LibroFmBooks
import com.vishnurajeevan.libroabs.libro.RetryPolicy
import com.vishnurajeevan.libroabs.libro.downloadTo
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.Tracks
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.server.ui.AbsPlanResult
import com.vishnurajeevan.libroabs.server.ui.AbsSyncOverview
import com.vishnurajeevan.libroabs.server.ui.AbsSyncPlan
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.time.Instant

/** Hands a freshly downloaded book over to the library it is listened from. */
interface LibraryImporter {
  /** Whether [import] does anything. */
  val enabled: Boolean

  suspend fun import(book: Book, directory: File)
}

/** Compares books already in Audiobookshelf with libro.fm, and brings them in line once the user agrees. */
interface AbsMetadataSync {
  val enabled: Boolean

  /** What syncing [book] would change. [downloadDir] is where this app saved it, if it did. */
  suspend fun plan(book: Book, downloadDir: File?): AbsPlanResult

  suspend fun planAll(books: List<Pair<Book, File?>>): AbsSyncOverview

  /**
   * Gives Audiobookshelf item [itemId] libro.fm's metadata for [book], limited to the [fields] (change keys) chosen;
   * `null` applies everything.
   */
  suspend fun apply(book: Book, downloadDir: File?, itemId: String, fields: Set<String>?, replaceCover: Boolean)

  suspend fun cover(itemId: String): Pair<ByteArray, String>?
}

/** How far into a book the user is, as Audiobookshelf reports it. */
data class ListeningProgress(
  val currentSeconds: Int,
  val durationSeconds: Int,
  val finished: Boolean,
  val startedAt: Instant?,
  val finishedAt: Instant?,
  val lastUpdate: Instant?,
)

/** Where the user's listening progress comes from. */
interface ListeningProgressSource {
  /** Whether progress can be read at all. */
  val progressAvailable: Boolean

  /** Progress for each of [books] that has been started, by ISBN. [books] pairs each book with its download folder. */
  suspend fun progress(books: List<Pair<Book, File?>>): Map<String, ListeningProgress>
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<LibraryImporter>())
@ContributesBinding(AppScope::class, binding = binding<ListeningProgressSource>())
@ContributesBinding(AppScope::class, binding = binding<AbsMetadataSync>())
class AudiobookshelfImporter(
  private val serverInfo: ServerInfo,
  httpClient: HttpClient,
  @Named("download") private val downloadClient: HttpClient,
  private val libroClient: LibroFmBooks,
  private val lfdLogger: Logger,
  @Io private val ioDispatcher: CoroutineDispatcher,
) : LibraryImporter, AbsMetadataSync, ListeningProgressSource {
  private val sync = serverInfo.audiobookshelf?.let {
    AudiobookshelfSync(AudiobookshelfClient(httpClient, it.url, it.apiKey), it, File(serverInfo.mediaDir), lfdLogger)
  }

  override val enabled: Boolean get() = serverInfo.writesAbsMetadataFiles && !serverInfo.dryRun

  override val progressAvailable: Boolean get() = sync != null

  override suspend fun progress(books: List<Pair<Book, File?>>): Map<String, ListeningProgress> {
    val sync = sync ?: return emptyMap()
    val items = sync.allItems()
    // Podcast episodes have their own entries; only whole items are books.
    val byItem = sync.mediaProgress().filter { it.episodeId == null }.associateBy { it.libraryItemId }
    return books.mapNotNull { (book, dir) ->
      val (item, _) = matchItem(book, items, dir?.let { sync.absPathFor(it) }) ?: return@mapNotNull null
      val progress = byItem[item.id] ?: return@mapNotNull null
      if (!progress.isFinished && progress.currentTime <= 0) return@mapNotNull null
      book.isbn to ListeningProgress(
        currentSeconds = progress.currentTime.toInt(),
        durationSeconds = progress.duration.toInt(),
        finished = progress.isFinished,
        startedAt = progress.startedAt?.let(Instant::fromEpochMilliseconds),
        finishedAt = progress.finishedAt?.let(Instant::fromEpochMilliseconds),
        lastUpdate = progress.lastUpdate?.let(Instant::fromEpochMilliseconds),
      )
    }.toMap()
  }

  override suspend fun import(book: Book, directory: File) {
    if (!enabled) return
    val sync = sync
    val metadata = book.toAbsMetadata().let { if (sync != null) it.spelledLike(sync.allItems()) else it }
    writeMetadataFile(metadata, directory)
    downloadCover(book, directory)
    if (sync == null) return

    val item = sync.add(metadata, directory, chapterTitles(book))
    writeMetadataFile(sync, item, directory)
    lfdLogger.i("Added ${book.title} to Audiobookshelf (${item.id})")
  }

  override suspend fun plan(book: Book, downloadDir: File?): AbsPlanResult {
    val sync = sync ?: return AbsPlanResult.Failure("Audiobookshelf is not configured")
    val items = sync.allItems()
    val (item, matchedBy) = matchItem(book, items, downloadDir?.let { sync.absPathFor(it) })
      ?: return AbsPlanResult.NotInAudiobookshelf
    return AbsPlanResult.Found(planFor(book, items, sync.item(item.id), matchedBy, chapterTitles(book)))
  }

  override suspend fun planAll(books: List<Pair<Book, File?>>): AbsSyncOverview = coroutineScope {
    val sync = sync ?: return@coroutineScope AbsSyncOverview(emptyList(), 0, emptyList(), listOf("Audiobookshelf is not configured"))
    val items = sync.allItems()
    val matched = books.map { (book, dir) -> book to matchItem(book, items, dir?.let { sync.absPathFor(it) }) }
    val permits = Semaphore(4)
    val results = matched.mapNotNull { (book, match) -> match?.let { book to it } }.map { (book, match) ->
      async {
        permits.withPermit {
          try {
            val full = sync.item(match.first.id)
            // Asking libro.fm for every book's chapter names is slow; only do it where Audiobookshelf's look like
            // "Track 001" and the counts line up.
            val titles = if (full.media.chapters.size == book.audiobook_info.track_count && full.media.chapters.lookGeneric()) {
              chapterTitles(book)
            } else {
              null
            }
            Result.success(planFor(book, items, full, match.second, titles))
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            Result.failure(IllegalStateException("${book.title}: ${e.message}", e))
          }
        }
      }
    }.awaitAll()
    val plans = results.mapNotNull { it.getOrNull() }
    AbsSyncOverview(
      plans = plans.filter { it.changes.isNotEmpty() }.sortedBy { it.title.lowercase() },
      upToDate = plans.count { it.changes.isEmpty() },
      notInAudiobookshelf = matched.filter { it.second == null }.map { it.first.title }.sortedBy { it.lowercase() },
      errors = results.mapNotNull { it.exceptionOrNull()?.message },
    )
  }

  override suspend fun apply(book: Book, downloadDir: File?, itemId: String, fields: Set<String>?, replaceCover: Boolean) {
    val sync = sync ?: error("Audiobookshelf is not configured")
    val metadata = book.toAbsMetadata().spelledLike(sync.allItems())
    val chosen = fields?.mapNotNull { key -> AbsField.entries.firstOrNull { it.name == key } }?.toSet() ?: AbsField.entries.toSet()
    val renameChapters = fields == null || CHAPTERS in fields
    val dir = downloadDir?.takeIf { it.isDirectory }
    val scratch = if (replaceCover && dir == null) withContext(ioDispatcher) { Files.createTempDirectory("abs-cover").toFile() } else null
    try {
      val cover = if (replaceCover) downloadCover(book, dir ?: scratch!!) else null
      val item = sync.applyTo(itemId, metadata, chosen, cover, if (renameChapters) chapterTitles(book) else null)
      if (dir != null) writeMetadataFile(sync, item, dir)
      lfdLogger.i("Synced libro.fm metadata for ${book.title} to Audiobookshelf ($itemId)")
    } finally {
      scratch?.let { withContext(ioDispatcher) { it.deleteRecursively() } }
    }
  }

  /**
   * Mirrors what Audiobookshelf now has into the book's `metadata.json`, so a rescan can't undo it or bring back a field
   * the user chose not to take from libro.fm. Chapters only go in when the item is this folder.
   */
  private suspend fun writeMetadataFile(sync: AudiobookshelfSync, item: AbsLibraryItem, directory: File) {
    val chapters = if (sync.absPathFor(directory) == item.path) item.media.chapters else emptyList()
    writeMetadataFile(item.media.metadata.toBookMetadata(), directory, chapters)
  }

  override suspend fun cover(itemId: String): Pair<ByteArray, String>? = sync?.cover(itemId)

  private fun planFor(
    book: Book,
    items: List<AbsLibraryItem>,
    item: AbsLibraryItem,
    matchedBy: String,
    chapterTitles: List<String>?,
  ): AbsSyncPlan {
    val changes = metadataChanges(item.media.metadata, book.toAbsMetadata().spelledLike(items)) +
      listOfNotNull(chapterChange(item.media.chapters, chapterTitles))
    return AbsSyncPlan(
      isbn = book.isbn,
      title = book.title,
      itemId = item.id,
      itemPath = item.path,
      matchedBy = matchedBy,
      changes = changes,
      coverUrl = book.coverUrl(),
      hasCover = item.media.coverPath != null,
    )
  }

  private suspend fun chapterTitles(book: Book): List<String>? = try {
    libroClient.fetchMp3DownloadMetadata(book.isbn).tracks.chapterTitles()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    lfdLogger.v("No chapter names for ${book.title}: ${e.message}")
    null
  }

  /** `metadata.json` lets Audiobookshelf match the book on any scan, with or without the API. */
  private suspend fun writeMetadataFile(metadata: AbsBookMetadata, directory: File, chapters: List<AbsChapter> = emptyList()) {
    withContext(ioDispatcher) {
      val target = File(directory, AudiobookshelfSync.METADATA)
      val temp = File(directory, ".${target.name}.tmp")
      temp.writeText(metadata.toMetadataFile(chapters))
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
  }

  /** Saves libro.fm's cover as `cover.jpg` in [directory]. A missing cover is no reason to fail the book. */
  private suspend fun downloadCover(book: Book, directory: File): File? {
    val url = book.coverUrl() ?: return null
    val cover = File(directory, AudiobookshelfSync.COVER)
    return try {
      withContext(ioDispatcher) { downloadClient.downloadTo(Url(url), cover, RetryPolicy(attempts = 3)) }
      cover
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      lfdLogger.i("Couldn't download the cover of ${book.title}: ${e.message}")
      null
    }
  }
}

/** libro.fm hands out protocol-relative cover links. */
internal fun Book.coverUrl(): String? = cover_url.takeIf { it.isNotBlank() }?.let { if (it.startsWith("//")) "https:$it" else it }

internal fun Book.toAbsMetadata() = AbsBookMetadata(
  title = title,
  authors = authors.map(::cleanPersonName).filter { it.isNotEmpty() },
  narrators = audiobook_info.narrators.map(::cleanPersonName).filter { it.isNotEmpty() },
  series = series?.takeIf { it.isNotBlank() },
  seriesSequence = series_num?.toString(),
  genres = genres.map { it.name }.filter { it.isNotBlank() },
  publisher = publisher.takeIf { it.isNotBlank() },
  publishedDate = publication_date.toString().take(10),
  description = description.takeIf { it.isNotBlank() },
  isbn = isbn,
)

/** The chapter names in track order, or `null` when libro.fm doesn't name every track. */
internal fun List<Tracks>.chapterTitles(): List<String>? = sortedBy { it.number }
  .map { it.chapter_title?.takeIf(String::isNotBlank) ?: return null }
  .takeIf { it.isNotEmpty() }
