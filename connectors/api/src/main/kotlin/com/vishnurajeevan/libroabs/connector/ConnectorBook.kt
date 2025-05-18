package com.vishnurajeevan.libroabs.connector

import kotlinx.datetime.LocalDate

data class ConnectorBook(
  val id: String,
  val title: String,
  val releaseDate: LocalDate? = null,
  val contributions: List<ConnectorContributor> = emptyList(),
  val connectorAudioBook: List<ConnectorAudioBookEdition> = emptyList()
)

data class ConnectorAudioBookEdition(
  val id: String,
  val isbn13: String?
)

data class ConnectorContributor(
  val id: String,
  val name: String
)
