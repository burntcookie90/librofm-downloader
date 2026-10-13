package com.vishnurajeevan.libroabs.audiobookshelf

import com.vishnurajeevan.libroabs.db.repo.LibroFmWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWrite
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.libro.LibroApiHandler
import com.vishnurajeevan.libroabs.libro.configureLibroApi
import com.vishnurajeevan.libroabs.libro.createLibroAPI
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.server.AbsImportMode
import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.AudiobookshelfConfig
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import com.vishnurajeevan.libroabs.storage.Storage
import com.vishnurajeevan.libroabs.storage.models.AuthToken
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import de.jensklingenberg.ktorfit.Ktorfit
import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.Test

/**
 * Imports one book from the real libro.fm library into a real Audiobookshelf. Opt-in: needs LIBRO_FM_USERNAME,
 * LIBRO_FM_PASSWORD, LIBRO_FM_HEADERS, ABS_URL, ABS_API_KEY and LIVE_IMPORT_TITLE. Runs in upload mode, so a book
 * Audiobookshelf already has elsewhere only gets its details updated.
 */
class AudiobookshelfLiveImportTest {
  private fun env(name: String) = System.getenv(name).orEmpty()

  private class MemoryStorage<T : Any>(var value: T) : Storage<T> {
    override suspend fun getData(): T = value
    override suspend fun update(update: suspend (T) -> T) { value = update(value) }
  }

  @Test
  fun importsABook() = runBlocking {
    val title = env("LIVE_IMPORT_TITLE")
    assumeTrue(title.isNotEmpty() && env("ABS_URL").isNotEmpty())
    val media = Files.createTempDirectory("live-media").toFile()
    val logger = object : Logger {
      override fun v(msg: String) = println("v: $msg")
      override fun i(msg: String) = println("i: $msg")
    }
    val info = ServerInfo(
      libroUserName = env("LIBRO_FM_USERNAME"), libroPassword = env("LIBRO_FM_PASSWORD"), port = 0, dataDir = "",
      mediaDir = media.path, syncInterval = "d", parallelCount = 1, dryRun = false, renameChapters = false,
      writeTitleTag = false, format = BookFormat.M4B_MP3_FALLBACK, downloadExtras = false, logLevel = ApplicationLogLevel.NONE,
      limit = -1, pathPattern = "", healthCheckHost = "", healthCheckId = null, trackerToken = null, trackerEndpoint = "",
      ffmpegPath = "", ffprobePath = "", audioQuality = "128k", skipTrackingIsbns = emptyList(),
      hardcoverSyncMode = TrackerSyncMode.ALL,
      audiobookshelf = AudiobookshelfConfig(env("ABS_URL"), env("ABS_API_KEY"), mode = AbsImportMode.UPLOAD),
    )
    val headers = env("LIBRO_FM_HEADERS").split(",").associate { it.substringBefore("=") to it.substringAfter("=") }
    val http = HttpClient()
    val api = Ktorfit.Builder().baseUrl("https://libro.fm/").httpClient(http.config { configureLibroApi(headers) })
      .converterFactories(ResponseConverterFactory()).build().createLibroAPI()
    val library = MemoryStorage(LibraryMetadata())
    val libro = LibroApiHandler(
      serverInfo = info, libroAPI = api, downloadClient = http, authTokenStorage = MemoryStorage(AuthToken()),
      wishlistSyncStatusRepo = object : LibroFmWishlistSyncStatusRepo {
        override suspend fun getSyncedIsbns() = emptyList<String>()
        override suspend fun allStatuses() = emptyMap<String, Boolean>()
      },
      libroLibraryStorage = library, lfdLogger = logger, ioDispatcher = Dispatchers.IO,
      dbWriter = object : DbWriter {
        override suspend fun write(write: DbWrite) {}
      },
    )
    libro.fetchLoginData(info.libroUserName, info.libroPassword)
    libro.fetchLibrary()
    val book = library.value.audiobooks.first { it.title.equals(title, ignoreCase = true) }
    println("libro.fm: $book")

    val dir = File(media, book.title).apply { mkdirs() }
    AudiobookshelfImporter(info, http, http, libro, logger, Dispatchers.IO).import(book, dir)
    println("metadata.json:\n" + File(dir, "metadata.json").readText())
    println("cover.jpg: ${File(dir, "cover.jpg").length()} bytes")
    media.deleteRecursively()
    Unit
  }
}
