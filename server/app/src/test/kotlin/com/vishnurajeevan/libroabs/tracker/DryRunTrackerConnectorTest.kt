package com.vishnurajeevan.libroabs.tracker

import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.DryRunTrackerConnector
import com.vishnurajeevan.libroabs.connector.ReadingStatus
import com.vishnurajeevan.libroabs.connector.TrackerRead
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DryRunTrackerConnectorTest {
  private val book = ConnectorBook("1", "All Systems Red", connectorAudioBook = listOf(ConnectorAudioBookEdition("2", "9781250000000")))

  @Test
  fun readsButNeverWrites() = runBlocking {
    val real = FakeTracker()
    val logged = mutableListOf<String>()
    val dryRun = DryRunTrackerConnector(real) { logged += it }

    dryRun.getOwnedBooks()
    dryRun.searchBooks("All Systems Red")
    assertNull(dryRun.createEdition(book))
    dryRun.markWanted(book)
    dryRun.markOwned(book)
    dryRun.setStatus("1", "2", ReadingStatus.READ, null)
    dryRun.saveRead("ub", TrackerRead(null, null, null, 10, "2"))

    assertEquals(listOf("search All Systems Red"), real.calls)
    assertEquals(5, logged.size)
  }
}
