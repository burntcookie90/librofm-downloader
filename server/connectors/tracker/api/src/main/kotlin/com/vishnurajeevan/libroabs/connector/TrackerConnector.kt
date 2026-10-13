package com.vishnurajeevan.libroabs.connector

interface TrackerConnector {
  suspend fun login()
  suspend fun getWantedBooks(): List<ConnectorBook>
  suspend fun getOwnedBooks(): List<ConnectorBook>
  suspend fun getReadBooks(): List<ConnectorBook>
  suspend fun getEditions(isbn13s: List<String>): List<ConnectorBook>

  /** Books matching a free-text search, in the tracker's order. */
  suspend fun searchBooks(query: String): List<TrackerCandidate>

  /**
   * Adds the audiobook in [book]'s first edition (by ISBN) to the tracker's book [book]. Returns the book with the new
   * edition, or `null` when nothing was created.
   */
  suspend fun createEdition(book: ConnectorBook, extras: EditionExtras = EditionExtras()): ConnectorBook?
  suspend fun markWanted(book: ConnectorBook)
  suspend fun markOwned(book: ConnectorBook)

  /** The user's entries for these books, with their reading history. */
  suspend fun getUserBooks(bookIds: List<String>): List<TrackerUserBook>

  /**
   * Gives book [bookId] [status] and edition [editionId], adding it to the user's books when [userBookId] is `null`.
   * Returns the user book's id.
   */
  suspend fun setStatus(bookId: String, editionId: String, status: ReadingStatus, userBookId: String?): String

  /** Adds [read] to the user book, or updates it when it has an id. */
  suspend fun saveRead(userBookId: String, read: TrackerRead)
}
