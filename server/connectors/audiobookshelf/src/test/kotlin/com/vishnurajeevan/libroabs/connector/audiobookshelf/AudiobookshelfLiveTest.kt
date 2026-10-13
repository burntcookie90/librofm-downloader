package com.vishnurajeevan.libroabs.connector.audiobookshelf

import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Read-only checks against a real server; runs only when ABS_URL and ABS_API_KEY are set. */
class AudiobookshelfLiveTest {
  private val url = System.getenv("ABS_URL").orEmpty()
  private val key = System.getenv("ABS_API_KEY").orEmpty()

  @Test
  fun readsTheLibrary() = runBlocking {
    assumeTrue(url.isNotEmpty() && key.isNotEmpty())
    val client = AudiobookshelfClient(HttpClient(), url, key)
    val library = client.libraries().first { it.mediaType == "book" }
    println("library ${library.name} folders ${library.folders.map { it.fullPath }}")
    val recent = client.recentlyAdded(library.id, limit = 3)
    println("recent ${recent.map { it.path }}")
    val first = recent.first()
    val title = client.item(first.id).media.metadata.title!!
    val found = client.search(library.id, title)
    println("search '$title' -> ${found.map { it.path }}")
    assertTrue(found.any { it.id == first.id })
    val chapters = client.item(first.id).media.chapters
    println("chapters ${chapters.size}: ${chapters.take(2)}")
    assertEquals(first.path, client.item(first.id).path)
  }
}
