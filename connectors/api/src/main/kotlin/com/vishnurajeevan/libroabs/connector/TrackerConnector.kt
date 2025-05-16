package com.vishnurajeevan.libroabs.connector

interface TrackerConnector {
  suspend fun getWantedBooks(): List<ConnectorBook>
  suspend fun getOwnedBooks(): List<ConnectorBook>
  suspend fun getEditions(isbn13s: List<String>): List<ConnectorBook>
  suspend fun markWanted(bookId: String)
  suspend fun markOwned(book: ConnectorBook)
}