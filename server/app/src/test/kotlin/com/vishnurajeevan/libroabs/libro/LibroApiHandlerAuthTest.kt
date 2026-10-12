package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.db.repo.LibroFmWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWrite
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import com.vishnurajeevan.libroabs.storage.Storage
import com.vishnurajeevan.libroabs.storage.models.AuthToken
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import de.jensklingenberg.ktorfit.Ktorfit
import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LibroApiHandlerAuthTest {
  /** Behaves like libro.fm: only the latest issued token works, and anything else gets 401 `{"error": ...}`. */
  private inner class FakeLibro {
    val logins = AtomicInteger(0)
    @Volatile var password = "right"
    @Volatile var rejectEverything = false
    @Volatile var validToken = "issued-0"

    val engine = MockEngine { request ->
      val json = headersOf(HttpHeaders.ContentType, "application/json")
      when (request.url.encodedPath) {
        "/oauth/token" -> {
          val body = String(request.body.toByteArray())
          if (!body.contains("\"password\":\"$password\"")) {
            respond("""{"error":"invalid_grant"}""", HttpStatusCode.Unauthorized, json)
          } else {
            validToken = "issued-${logins.incrementAndGet()}"
            respond("""{"access_token":"$validToken"}""", HttpStatusCode.OK, json)
          }
        }
        else ->
          if (!rejectEverything && request.headers[HttpHeaders.Authorization] == "Bearer $validToken") {
            respond("""{"page":1,"total_pages":1,"audiobooks":[]}""", HttpStatusCode.OK, json)
          } else {
            respond("""{"error":"Not authorized."}""", HttpStatusCode.Unauthorized, json)
          }
      }
    }
  }

  private class MemoryStorage<T : Any>(@Volatile var value: T) : Storage<T> {
    private val mutex = Mutex()
    override suspend fun getData(): T = value
    override suspend fun update(update: suspend (T) -> T) = mutex.withLock { value = update(value) }
  }

  private val libro = FakeLibro()
  private val tokens = MemoryStorage(AuthToken(token = "expired", username = "reader"))
  private val library = MemoryStorage(LibraryMetadata(page = 7))

  private fun handler(): LibroApiHandler {
    val client = HttpClient(libro.engine) { configureLibroApi(emptyMap()) }
    val api = Ktorfit.Builder().baseUrl("https://libro.fm/").httpClient(client)
      .converterFactories(ResponseConverterFactory()).build().createLibroAPI()
    return LibroApiHandler(
      serverInfo = ServerInfo(
        libroUserName = "reader", libroPassword = "right", port = 0, dataDir = "", mediaDir = "", syncInterval = "d",
        parallelCount = 1, dryRun = false, renameChapters = false, writeTitleTag = false, format = BookFormat.MP3,
        downloadExtras = false, logLevel = ApplicationLogLevel.NONE, limit = -1, pathPattern = "", healthCheckHost = "",
        healthCheckId = null, trackerToken = null, trackerEndpoint = "", ffmpegPath = "", ffprobePath = "",
        audioQuality = "128k", skipTrackingIsbns = emptyList(), hardcoverSyncMode = TrackerSyncMode.ALL,
      ),
      libroAPI = api,
      downloadClient = client,
      authTokenStorage = tokens,
      wishlistSyncStatusRepo = object : LibroFmWishlistSyncStatusRepo {
        override suspend fun getSyncedIsbns() = emptyList<String>()
        override suspend fun allStatuses() = emptyMap<String, Boolean>()
      },
      libroLibraryStorage = library,
      lfdLogger = object : Logger {
        override fun v(msg: String) {}
        override fun i(msg: String) {}
      },
      ioDispatcher = Dispatchers.IO,
      dbWriter = object : DbWriter {
        override suspend fun write(write: DbWrite) {}
      },
    )
  }

  @Test
  fun rejectedTokenLogsInAgainAndRetries() = runBlocking {
    val handler = handler()
    handler.fetchLoginData("reader", "right")
    assertEquals(0, libro.logins.get(), "a saved token for the same account is reused")

    handler.fetchLibrary()
    assertEquals(1, libro.logins.get())
    assertEquals("issued-1", tokens.value.token)
    assertEquals(1, library.value.page)
    assertNull(library.value.error)
  }

  @Test
  fun parallelRejectionsLogInOnlyOnce() = runBlocking {
    val handler = handler()
    handler.fetchLoginData("reader", "right")
    coroutineScope { List(8) { async(Dispatchers.IO) { handler.fetchLibrary() } }.awaitAll() }
    assertEquals(1, libro.logins.get())
  }

  @Test
  fun aRejectedLibraryIsNeverSavedOverTheRealOne() = runBlocking {
    val handler = handler()
    handler.fetchLoginData("reader", "right")
    libro.rejectEverything = true
    assertFailsWith<LibroUnauthorizedException> { handler.fetchLibrary() }
    assertEquals(7, library.value.page, "the previous library is kept")
  }

  @Test
  fun changingAccountLogsIn() = runBlocking {
    tokens.value = AuthToken(token = "issued-0", username = "someone-else")
    handler().fetchLoginData("reader", "right")
    assertEquals(1, libro.logins.get())
    assertEquals("reader", tokens.value.username)
  }

  @Test
  fun wrongPasswordExplainsItself() = runBlocking {
    tokens.value = AuthToken()
    libro.password = "changed"
    val e = assertFailsWith<IllegalArgumentException> { handler().fetchLoginData("reader", "right") }
    assertEquals("libro.fm rejected the login; check LIBRO_FM_USERNAME and LIBRO_FM_PASSWORD", e.message)
  }
}
