package com.vishnurajeevan.libroabs.audiobookshelf

import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsBookMetadata
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AudiobookshelfClient
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AudiobookshelfException
import com.vishnurajeevan.libroabs.download.testBook
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.libro.Genre
import com.vishnurajeevan.libroabs.models.libro.Tracks
import com.vishnurajeevan.libroabs.models.server.AbsImportMode
import com.vishnurajeevan.libroabs.models.server.AudiobookshelfConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AudiobookshelfSyncTest {
  /** Just enough of Audiobookshelf: books appear a couple of polls after it hears about them. */
  private class FakeAbs {
    class Item(val id: String, val path: String, val title: String, val isbn: String?, val chapters: Int)

    val requests = CopyOnWriteArrayList<String>()
    val bodies = CopyOnWriteArrayList<String>()
    val items = CopyOnWriteArrayList<Item>()
    var libraries = """[{"id":"lib1","name":"Books","mediaType":"book","folders":[{"id":"fol1","fullPath":"/books/Audiobooks"}]}]"""
    var watcherWorks = true
    var scanFindsIt = true
    var chapters = 2
    private var pending: Item? = null
    private var pollsUntilAdded = 2

    val engine = MockEngine { request ->
      val path = request.url.encodedPath
      val body = String(request.body.toByteArray())
      requests += "${request.method.value} $path"
      if (request.method != HttpMethod.Get) bodies += body
      val json = headersOf(HttpHeaders.ContentType, "application/json")
      when {
        request.headers[HttpHeaders.Authorization] != "Bearer key" -> respond("", HttpStatusCode.Unauthorized)
        path == "/api/libraries" -> respond("""{"libraries":$libraries}""", HttpStatusCode.OK, json)
        path == "/api/watcher/update" -> {
          if (watcherWorks) {
            val file = Json.parseToJsonElement(body).jsonObject["path"]!!.jsonPrimitive.content
            pending = pending ?: Item("new", file.substringBeforeLast('/'), "Murderbot", "978", chapters)
          }
          respond("OK")
        }
        path == "/api/upload" -> {
          pending = Item("uploaded", "/books/Audiobooks/Martha Wells/The Murderbot Diaries/Murderbot", "Murderbot", "978", chapters)
          respond("OK")
        }
        path == "/api/libraries/lib1/scan" -> {
          if (scanFindsIt) pending = pending ?: Item("scanned", "/books/Audiobooks/Martha Wells/Murderbot", "Murderbot", "978", chapters)
          pollsUntilAdded = 0
          respond("OK")
        }
        path == "/api/libraries/lib1/items" -> {
          pending?.let { if (pollsUntilAdded-- <= 0) { items += it; pending = null } }
          respond("""{"results":[${items.reversed().joinToString { it.json() }}]}""", HttpStatusCode.OK, json)
        }
        path == "/api/libraries/lib1/search" -> {
          val q = request.url.parameters["q"]!!
          val hits = items.filter { it.title == q || it.isbn == q }
          respond("""{"book":[${hits.joinToString { """{"libraryItem":${it.json()}}""" }}]}""", HttpStatusCode.OK, json)
        }
        path.startsWith("/api/items/") -> {
          val id = path.removePrefix("/api/items/").substringBefore('/')
          val item = items.first { it.id == id }
          if (request.method == HttpMethod.Get) respond(item.json(), HttpStatusCode.OK, json) else respond("OK")
        }
        else -> respond("", HttpStatusCode.NotFound)
      }
    }

    fun Item.json() = """{"id":"$id","path":"$path","media":{"metadata":{"title":"$title","isbn":${isbn?.let { "\"$it\"" }}},
      "chapters":[${(0 until chapters).joinToString { """{"id":$it,"start":${it * 10}.0,"end":${it * 10 + 10}.0,"title":"Track $it"}""" }}]}}"""
  }

  private val abs = FakeAbs()
  private val media = Files.createTempDirectory("media").toFile()
  private val bookDir = File(media, "Martha Wells/Murderbot").apply {
    mkdirs()
    File(this, "Murderbot.m4b").writeText("audio")
    File(this, "cover.jpg").writeText("jpg")
    File(this, "metadata.json").writeText("{}")
  }
  private val logs = CopyOnWriteArrayList<String>()

  @AfterTest
  fun cleanup() {
    media.deleteRecursively()
  }

  private val metadata = AbsBookMetadata(title = "Murderbot", authors = listOf("Martha Wells"), isbn = "978", series = "The Murderbot Diaries", seriesSequence = "1")

  private fun sync(config: AudiobookshelfConfig = AudiobookshelfConfig("https://abs.example/", "key")) = AudiobookshelfSync(
    client = AudiobookshelfClient(HttpClient(abs.engine), config.url, config.apiKey),
    config = config,
    mediaDir = media,
    logger = object : Logger {
      override fun v(msg: String) { logs += msg }
      override fun i(msg: String) { logs += msg }
    },
    pollIntervalMs = 1,
    scanFallbackAfterMs = 5,
    timeoutMs = 20,
  )

  @Test
  fun aNewBookIsAddedByPathAndGivenLibroMetadata() = runBlocking {
    val item = sync().add(metadata, bookDir, listOf("Chapter 1", "Chapter 2"))

    assertEquals("/books/Audiobooks/Martha Wells/Murderbot", item.path)
    assertEquals(3, abs.requests.count { it == "POST /api/watcher/update" }, "one per file")
    assertTrue(abs.bodies.any { "\"path\":\"/books/Audiobooks/Martha Wells/Murderbot/Murderbot.m4b\"" in it })
    assertTrue("POST /api/libraries/lib1/scan" !in abs.requests, "the watcher was enough")

    val update = Json.parseToJsonElement(abs.bodies.first { it.contains("\"metadata\"") }).jsonObject["metadata"]!!.jsonObject
    assertEquals("Murderbot", update["title"]!!.jsonPrimitive.content)
    assertEquals("978", update["isbn"]!!.jsonPrimitive.content)
    val series = update["series"]!!.jsonArray.single().jsonObject
    assertEquals("The Murderbot Diaries", series["name"]!!.jsonPrimitive.content)
    assertEquals("1", series["sequence"]!!.jsonPrimitive.content)
    assertEquals("Martha Wells", update["authors"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)

    assertTrue("POST /api/items/new/cover" in abs.requests)
    val chapters = Json.parseToJsonElement(abs.bodies.first { it.contains("\"chapters\"") }).jsonObject["chapters"]!!.jsonArray
    assertEquals(listOf("Chapter 1", "Chapter 2"), chapters.map { it.jsonObject["title"]!!.jsonPrimitive.content })
    assertEquals(10.0, chapters[1].jsonObject["start"]!!.jsonPrimitive.content.toDouble(), "timings are kept")
  }

  @Test
  fun aRedownloadedBookIsRescannedInPlace() = runBlocking {
    abs.items += FakeAbs.Item("old", "/books/Audiobooks/Martha Wells/Murderbot", "Murderbot", null, 2)
    val item = sync().add(metadata, bookDir, null)
    assertEquals("old", item.id)
    assertTrue("POST /api/items/old/scan" in abs.requests)
    assertTrue("POST /api/watcher/update" !in abs.requests)
    assertTrue(abs.requests.none { it.endsWith("/chapters") })
  }

  @Test
  fun withoutAWatcherTheLibraryIsScanned() = runBlocking {
    abs.watcherWorks = false
    val item = sync().add(metadata, bookDir, null)
    assertEquals("scanned", item.id)
    assertTrue("POST /api/libraries/lib1/scan" in abs.requests)
  }

  @Test
  fun uploadModeSendsTheFiles() = runBlocking {
    val item = sync(AudiobookshelfConfig("https://abs.example", "key", mode = AbsImportMode.UPLOAD)).add(metadata, bookDir, null)
    assertEquals("uploaded", item.id)
    val upload = abs.bodies.first { it.contains("name=\"library\"") }
    listOf("Murderbot.m4b", "cover.jpg", "metadata.json").forEach { assertTrue("filename=$it" in upload || "filename=\"$it\"" in upload, it) }
    assertTrue("name=\"series\"" in upload)
    assertTrue("POST /api/watcher/update" !in abs.requests)
  }

  @Test
  fun uploadModeReplacesItsOwnEarlierUpload() = runBlocking {
    abs.items += FakeAbs.Item("mine", "/books/Audiobooks/Martha Wells/The Murderbot Diaries/Murderbot", "Murderbot", "978", 2)
    val item = sync(AudiobookshelfConfig("https://abs.example", "key", mode = AbsImportMode.UPLOAD)).add(metadata, bookDir, null)
    assertEquals("mine", item.id)
    assertTrue("POST /api/upload" in abs.requests)
    assertTrue("POST /api/items/mine/scan" in abs.requests)
  }

  @Test
  fun uploadModeNeverDuplicatesABookFiledElsewhere() = runBlocking {
    abs.items += FakeAbs.Item("theirs", "/books/Audiobooks/Martha Wells/Murderbot", "Murderbot", null, 2)
    val item = sync(AudiobookshelfConfig("https://abs.example", "key", mode = AbsImportMode.UPLOAD)).add(metadata, bookDir, null)
    assertEquals("theirs", item.id)
    assertTrue("POST /api/upload" !in abs.requests)
    assertTrue(abs.bodies.any { it.contains("\"metadata\"") }, "its details are still updated")
  }

  @Test
  fun chapterNamesAreLeftAloneWhenTheCountsDiffer() = runBlocking {
    abs.chapters = 3
    sync().add(metadata, bookDir, listOf("Chapter 1", "Chapter 2"))
    assertTrue(abs.requests.none { it.endsWith("/chapters") })
  }

  @Test
  fun severalLibrariesNeedAnId() = runBlocking {
    abs.libraries = """[{"id":"a","name":"Books","mediaType":"book"},{"id":"b","name":"Kids","mediaType":"book"},
      {"id":"c","name":"Podcasts","mediaType":"podcast"}]"""
    val e = assertFailsWith<AudiobookshelfException> { sync().add(metadata, bookDir, null) }
    assertEquals("Audiobookshelf has 2 book libraries; set ABS_LIBRARY_ID to one of a (Books), b (Kids)", e.message)
  }

  @Test
  fun aBookOutsideTheMediaFolderIsRefused() = runBlocking {
    val elsewhere = Files.createTempDirectory("elsewhere").toFile()
    assertFailsWith<AudiobookshelfException> { sync().add(metadata, elsewhere, null) }
    elsewhere.delete()
    Unit
  }

  @Test
  fun givesUpWhenTheBookNeverAppears() = runBlocking {
    abs.watcherWorks = false
    abs.scanFindsIt = false
    val e = assertFailsWith<AudiobookshelfException> { sync().add(metadata, bookDir, null) }
    assertEquals("Audiobookshelf didn't add Murderbot within 0 seconds", e.message)
    assertTrue("POST /api/libraries/lib1/scan" in abs.requests)
    Unit
  }

  @Test
  fun libroBooksMapToAbsMetadata() {
    val book = testBook("978", "All Systems Red").copy(
      authors = listOf("Martha Wells"),
      series = "The Murderbot Diaries",
      series_num = 1,
      genres = listOf(Genre("Science Fiction"), Genre(" ")),
      description = "<p>A murderous android</p>",
      publisher = "",
    )
    val m = book.toAbsMetadata()
    assertEquals("2020-01-01", m.publishedDate)
    assertEquals("2020", m.publishedYear)
    assertEquals(listOf("Science Fiction"), m.genres)
    assertEquals(null, m.publisher)
    assertEquals("1", m.seriesSequence)
  }

  @Test
  fun chapterTitlesNeedEveryTrackNamed() {
    assertEquals(listOf("One", "Two"), listOf(Tracks(2, "Two"), Tracks(1, "One")).chapterTitles())
    assertEquals(null, listOf(Tracks(1, "One"), Tracks(2, null)).chapterTitles())
    assertEquals(null, emptyList<Tracks>().chapterTitles())
  }
}
