package com.vishnurajeevan.libroabs.audiobookshelf

import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsBookMetadata
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AudiobookshelfClient
import com.vishnurajeevan.libroabs.libro.LibroFmBooks
import com.vishnurajeevan.libroabs.libro.RetryPolicy
import com.vishnurajeevan.libroabs.libro.downloadTo
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.Tracks
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Hands a freshly downloaded book over to the library it is listened from. */
interface LibraryImporter {
  /** Whether [import] does anything. */
  val enabled: Boolean

  suspend fun import(book: Book, directory: File)
}

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AudiobookshelfImporter(
  private val serverInfo: ServerInfo,
  httpClient: HttpClient,
  @Named("download") private val downloadClient: HttpClient,
  private val libroClient: LibroFmBooks,
  private val lfdLogger: Logger,
  @Io private val ioDispatcher: CoroutineDispatcher,
) : LibraryImporter {
  private val sync = serverInfo.audiobookshelf?.let {
    AudiobookshelfSync(AudiobookshelfClient(httpClient, it.url, it.apiKey), it, File(serverInfo.mediaDir), lfdLogger)
  }

  override val enabled: Boolean get() = serverInfo.writesAbsMetadataFiles && !serverInfo.dryRun

  override suspend fun import(book: Book, directory: File) {
    if (!enabled) return
    val metadata = book.toAbsMetadata()
    writeMetadataFiles(book, metadata, directory)
    val sync = sync ?: return

    val chapterTitles = try {
      libroClient.fetchMp3DownloadMetadata(book.isbn).tracks.chapterTitles()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      lfdLogger.v("No chapter names for ${book.title}: ${e.message}")
      null
    }
    val item = sync.add(metadata, directory, chapterTitles)
    lfdLogger.i("Added ${book.title} to Audiobookshelf (${item.id})")
  }

  /** `metadata.json` and `cover.jpg` let Audiobookshelf match the book on any scan, with or without the API. */
  private suspend fun writeMetadataFiles(book: Book, metadata: AbsBookMetadata, directory: File) {
    withContext(ioDispatcher) {
      val target = File(directory, AudiobookshelfSync.METADATA)
      val temp = File(directory, ".${target.name}.tmp")
      temp.writeText(metadata.toMetadataFile())
      Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    if (book.cover_url.isBlank()) return
    try {
      withContext(ioDispatcher) {
        downloadClient.downloadTo(Url(book.cover_url), File(directory, AudiobookshelfSync.COVER), RetryPolicy(attempts = 3))
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // A missing cover is no reason to fail the book; Audiobookshelf falls back to the embedded one.
      lfdLogger.i("Couldn't download the cover of ${book.title}: ${e.message}")
    }
  }
}

internal fun Book.toAbsMetadata() = AbsBookMetadata(
  title = title,
  authors = authors,
  narrators = audiobook_info.narrators,
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
