package com.vishnurajeevan.libroabs.libro

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.Url
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DownloadsTest {
  private val dir = Files.createTempDirectory("downloads").toFile()

  private fun client(status: HttpStatusCode, body: String) = HttpClient(MockEngine { respond(body, status) })

  @Test
  fun savesSuccessfulResponses() = runBlocking {
    val file = File(dir, "book.m4b")
    client(HttpStatusCode.OK, "audio").downloadTo(Url("https://cdn.example/book.m4b"), file)
    assertEquals("audio", file.readText())
  }

  @Test
  fun errorResponsesFailAndLeaveNoFile() = runBlocking {
    val file = File(dir, "book.m4b")
    val e = assertFailsWith<IOException> {
      client(HttpStatusCode.Forbidden, "<Error>AccessDenied</Error>").downloadTo(Url("https://cdn.example/book.m4b?sig=x"), file)
    }
    assertEquals("HTTP 403 downloading from cdn.example", e.message)
    assertFalse(file.exists())
  }

  private val noWait = RetryPolicy(attempts = 3, delayMillis = { 0 })

  /** Answers each request with the next of [responses], recording the Range header asked for. */
  private class Scripted(vararg val responses: Triple<HttpStatusCode, String, Long?>) {
    val ranges = mutableListOf<String?>()
    val client = HttpClient(MockEngine { request ->
      ranges += request.headers[HttpHeaders.Range]
      val (status, body, length) = responses[ranges.size - 1]
      val headers = length?.let { headersOf(HttpHeaders.ContentLength, it.toString()) } ?: headersOf()
      respond(body, status, headers)
    })
  }

  @Test
  fun serverErrorsAreRetried() = runBlocking {
    val file = File(dir, "book.m4b")
    val server = Scripted(Triple(HttpStatusCode.ServiceUnavailable, "", null), Triple(HttpStatusCode.OK, "audio", null))
    server.client.downloadTo(Url("https://cdn.example/book.m4b"), file, noWait)
    assertEquals("audio", file.readText())
    assertEquals(2, server.ranges.size)
  }

  @Test
  fun aCutOffDownloadResumesWhereItStopped() = runBlocking {
    val file = File(dir, "book.m4b")
    val server = Scripted(
      Triple(HttpStatusCode.OK, "abc", 10),
      Triple(HttpStatusCode.PartialContent, "defghij", 7),
    )
    server.client.downloadTo(Url("https://cdn.example/book.m4b"), file, noWait)
    assertEquals("abcdefghij", file.readText())
    assertEquals(listOf(null, "bytes=3-"), server.ranges)
  }

  @Test
  fun aServerIgnoringTheRangeStartsOver() = runBlocking {
    val file = File(dir, "book.m4b")
    val server = Scripted(Triple(HttpStatusCode.OK, "abc", 10), Triple(HttpStatusCode.OK, "abcdefghij", 10))
    server.client.downloadTo(Url("https://cdn.example/book.m4b"), file, noWait)
    assertEquals("abcdefghij", file.readText())
  }

  @Test
  fun aFailedDownloadKeepsTheExistingCopy() = runBlocking {
    val file = File(dir, "book.m4b").apply { writeText("the old copy") }
    val server = Scripted(*Array(3) { Triple(HttpStatusCode.BadGateway, "", null) })
    val e = assertFailsWith<IOException> { server.client.downloadTo(Url("https://cdn.example/book.m4b"), file, noWait) }
    assertEquals("HTTP 502 downloading from cdn.example (gave up after 3 attempts)", e.message)
    assertEquals("the old copy", file.readText())
    assertFalse(File(dir, "book.m4b.part").exists())
  }

  @Test
  fun expiredLinksAreNotRetried() = runBlocking {
    val server = Scripted(Triple(HttpStatusCode.Forbidden, "", null))
    assertFailsWith<PermanentDownloadException> {
      server.client.downloadTo(Url("https://cdn.example/book.m4b"), File(dir, "book.m4b"), noWait)
    }
    assertEquals(1, server.ranges.size)
  }

  @Test
  fun movesStagedFilesIntoPlace() {
    val staging = File(dir, "staging")
    val files = listOf(File(staging, "a.mp3"), File(staging, "disc/b.mp3")).onEach {
      it.parentFile.mkdirs(); it.writeText(it.name)
    }
    val book = File(dir, "book").apply { mkdirs() }
    File(book, "a.mp3").writeText("old")
    val moved = moveInto(staging, files, book)
    assertEquals(listOf(File(book, "a.mp3"), File(book, "disc/b.mp3")).map { it.canonicalFile }, moved)
    assertEquals("a.mp3", File(book, "a.mp3").readText())
  }

  @Test
  fun extractsZipsAndReturnsTheFiles() {
    val zip = zip("a.mp3" to "1", "disc/b.mp3" to "2")
    val files = extractZip(zip, File(dir, "book").apply { mkdirs() })
    assertEquals(listOf("a.mp3", "b.mp3"), files.map { it.name })
    assertEquals("2", File(dir, "book/disc/b.mp3").readText())
  }

  @Test
  fun anArchiveWithoutFilesIsAnError() {
    val notAZip = File(dir, "part-0.zip").apply { writeText("<Error>Expired</Error>") }
    assertFailsWith<IOException> { extractZip(notAZip, dir) }
  }

  @Test
  fun m4bNamesComeFromTheDispositionOrThePath() {
    assertEquals(
      "The Book.m4b",
      m4bFilename(Url("https://cdn.example/x/abc?response-content-disposition=attachment%3B%20filename%3D%22The+Book.m4b%22")),
    )
    assertEquals("abc.m4b", m4bFilename(Url("https://cdn.example/x/abc.m4b")))
  }

  private fun zip(vararg entries: Pair<String, String>): File {
    val file = File(dir, "test.zip")
    ZipOutputStream(file.outputStream()).use { out ->
      entries.forEach { (name, text) ->
        out.putNextEntry(ZipEntry(name))
        out.write(text.toByteArray())
        out.closeEntry()
      }
    }
    return file
  }
}
