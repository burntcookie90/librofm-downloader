package com.vishnurajeevan.libroabs.connector

data class ConnectorBook(
  val id: String,
  val name: String,
  val connectorAudioBook: List<ConnectorAudioBookEdition>
)

data class ConnectorAudioBookEdition(
  val id: String,
  val isbn13: String?
)
