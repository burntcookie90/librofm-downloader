package com.vishnurajeevan.libroabs.libro

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
