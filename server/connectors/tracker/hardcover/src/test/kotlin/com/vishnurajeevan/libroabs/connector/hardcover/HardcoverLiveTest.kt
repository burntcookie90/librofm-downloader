package com.vishnurajeevan.libroabs.connector.hardcover

import com.vishnurajeevan.libroabs.models.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Read-only checks against the real Hardcover API; runs only when HARDCOVER_TOKEN is set. Nothing is changed. */
class HardcoverLiveTest {
  private val token = System.getenv("HARDCOVER_TOKEN").orEmpty().removePrefix("Bearer ").trim()

  @Test
  fun readsTheAccount() = runBlocking {
    assumeTrue(token.isNotEmpty())
    val connector = HardcoverTrackerConnector(
      token = token,
      endpoint = "https://api.hardcover.app/v1/graphql",
      logger = object : Logger {
        override fun v(msg: String) = Unit
        override fun i(msg: String) = println(msg)
      },
      dispatcher = Dispatchers.IO,
    )
    connector.login()

    val wanted = connector.getWantedBooks()
    val read = connector.getReadBooks()
    val owned = connector.getOwnedBooks()
    println("want to read ${wanted.size}, read ${read.size}, owned ${owned.size}")

    val found = connector.searchBooks("All Systems Red Martha Wells")
    println("search -> ${found.take(3).map { "${it.title} by ${it.authors.map { a -> a.name }}" }}")
    assertTrue(found.any { it.title == "All Systems Red" && it.authors.any { a -> a.name == "Martha Wells" } })
    println("user books -> ${connector.getUserBooks(found.take(3).map { it.id })}")

    // All Systems Red's Tor.com hardcover, for accounts with nothing on their lists yet.
    val isbn = (owned + wanted + read).flatMap { it.connectorAudioBook }.firstNotNullOfOrNull { it.isbn13 } ?: "9780765397539"
    val editions = connector.getEditions(listOf(isbn))
    println("editions for $isbn -> ${editions.map { it.title }}")
    assertTrue(editions.flatMap { it.connectorAudioBook }.any { it.isbn13 == isbn })
  }
}
