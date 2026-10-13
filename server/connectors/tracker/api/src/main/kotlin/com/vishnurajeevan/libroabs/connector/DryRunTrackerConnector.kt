package com.vishnurajeevan.libroabs.connector

/** Reads from [delegate] as usual, but only logs the changes it would make. */
class DryRunTrackerConnector(
  private val delegate: TrackerConnector,
  private val log: (String) -> Unit,
) : TrackerConnector by delegate {
  override suspend fun createEdition(book: ConnectorBook): ConnectorBook? {
    val isbn = book.connectorAudioBook.firstNotNullOfOrNull { it.isbn13 }
    log("Dry run: would create an audiobook edition of ${book.title} (ISBN $isbn) and add it to your lists")
    return null
  }

  override suspend fun markWanted(book: ConnectorBook) {
    log("Dry run: would add ${book.title} to Want to Read")
  }

  override suspend fun markOwned(book: ConnectorBook) {
    log("Dry run: would mark ${book.title} as owned")
  }
}
