package com.vishnurajeevan.libroabs.tracker

import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.DryRunTrackerConnector
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DryRunTrackerConnectorTest {
  private val book = ConnectorBook("1", "All Systems Red", connectorAudioBook = listOf(ConnectorAudioBookEdition("2", "9781250000000")))

  private class Recording : TrackerConnector {
    val calls = mutableListOf<String>()
    override suspend fun login() { calls += "login" }
    override suspend fun getWantedBooks() = emptyList<ConnectorBook>().also { calls += "wanted" }
    override suspend fun getOwnedBooks() = emptyList<ConnectorBook>().also { calls += "owned" }
    override suspend fun getReadBooks() = emptyList<ConnectorBook>().also { calls += "read" }
    override suspend fun getEditions(isbn13s: List<String>) = emptyList<ConnectorBook>().also { calls += "editions" }
    override suspend fun createEdition(book: ConnectorBook): ConnectorBook? = book.also { calls += "createEdition" }
    override suspend fun markWanted(book: ConnectorBook) { calls += "markWanted" }
    override suspend fun markOwned(book: ConnectorBook) { calls += "markOwned" }
    override suspend fun searchByTitle(title: String, author: String): ConnectorBook? = null.also { calls += "search" }
  }

  @Test
  fun readsButNeverWrites() = runBlocking {
    val real = Recording()
    val logged = mutableListOf<String>()
    val dryRun = DryRunTrackerConnector(real) { logged += it }

    dryRun.login()
    dryRun.getOwnedBooks()
    dryRun.getEditions(listOf("9781250000000"))
    assertNull(dryRun.createEdition(book))
    dryRun.markWanted(book)
    dryRun.markOwned(book)

    assertEquals(listOf("login", "owned", "editions"), real.calls)
    assertEquals(3, logged.size)
  }
}
