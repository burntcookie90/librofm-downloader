package com.vishnurajeevan.libroabs.connector

data class ConnectorBook(
  val id: String,
  val name: String,
  val connectorAudioBook: ConnectorAudioBook?
)

data class ConnectorAudioBook(
  val id: String,
  val isbn13: String?
)
