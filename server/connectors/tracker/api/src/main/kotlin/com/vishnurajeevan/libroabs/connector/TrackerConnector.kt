package com.vishnurajeevan.libroabs.connector

interface TrackerConnector {
  suspend fun login()
  suspend fun getWantedBooks(): List<ConnectorBook>
  suspend fun getOwnedBooks(): List<ConnectorBook>
  suspend fun getEditions(isbn13s: List<String>): List<ConnectorBook>
  suspend fun createEdition(book: ConnectorBook): ConnectorBook?
  suspend fun markWanted(book: ConnectorBook)
  suspend fun markOwned(book: ConnectorBook)
  suspend fun searchByTitle(title: String, author: String): ConnectorBook?
}