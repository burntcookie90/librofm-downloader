package com.vishnurajeevan.libroabs.connector

interface MetadataConnector {
  suspend fun getWantedBooks(): List<ConnectorBook>
  suspend fun markWanted(bookId: String)
  suspend fun markOwned(bookId: String)
}