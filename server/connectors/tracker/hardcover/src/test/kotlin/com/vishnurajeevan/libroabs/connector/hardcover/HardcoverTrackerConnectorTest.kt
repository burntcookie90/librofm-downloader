package com.vishnurajeevan.libroabs.connector.hardcover

import com.sun.net.httpserver.HttpServer
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.TrackerException
import com.vishnurajeevan.libroabs.connector.TrackerUnavailableException
import com.vishnurajeevan.libroabs.models.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HardcoverTrackerConnectorTest {
  private val requests = mutableListOf<Pair<String?, String>>()
  private var status = 200
  private var reply = "{}"

  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/graphql") { exchange ->
      requests += exchange.requestHeaders.getFirst("Authorization") to exchange.requestBody.readBytes().decodeToString()
      val body = reply.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(status, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    start()
  }

  private fun connector(token: String = "abc") = HardcoverTrackerConnector(
    token = token,
    endpoint = "http://127.0.0.1:${server.address.port}/graphql",
    logger = object : Logger {
      override fun v(msg: String) = Unit
      override fun i(msg: String) = Unit
    },
    dispatcher = Dispatchers.IO,
  )

  private val book = ConnectorBook(
    id = "42",
    title = "All Systems Red",
    connectorAudioBook = listOf(ConnectorAudioBookEdition(id = "7", isbn13 = "9781250000000")),
  )

  @AfterTest
  fun stop() = server.stop(0)

  @Test
  fun aRejectedTokenSaysSo() = runBlocking {
    status = 401
    reply = """{"error":"invalid_token"}"""
    val error = assertFailsWith<TrackerUnavailableException> { connector().login() }
    assertTrue("HARDCOVER_TOKEN" in error.message!!, error.message)
  }

  @Test
  fun theTokenIsSentOnceEvenWhenPastedWithItsPrefix() = runBlocking {
    reply = """{"data":{"me":[{"id":1,"username":"reader"}]}}"""
    connector(token = "Bearer abc").login()
    assertEquals("Bearer abc", requests.single().first)
  }

  @Test
  fun graphQlErrorsAreNotTakenForSuccess() = runBlocking {
    reply = """{"errors":[{"message":"field 'nope' not found in type: 'query_root'"}]}"""
    val error = assertFailsWith<TrackerException> { connector().getEditions(listOf("9781250000000")) }
    assertTrue("field 'nope' not found" in error.message!!, error.message)
  }

  @Test
  fun wantToReadUsesTheAudiobookEdition() = runBlocking {
    reply = """{"data":{"insert_user_book":{"id":99,"error":null}}}"""
    connector().markWanted(book)
    val body = requests.single().second
    assertTrue(""""bookId":42""" in body && """"editionId":7""" in body, body)
  }

  @Test
  fun aRefusedWantToReadIsAnError() = runBlocking {
    reply = """{"data":{"insert_user_book":{"id":null,"error":"We weren't able to find that book."}}}"""
    val error = assertFailsWith<TrackerException> { connector().markWanted(book) }
    assertTrue("weren't able to find that book" in error.message!!, error.message)
  }

  @Test
  fun aRefusedOwnedIsAnError() = runBlocking {
    reply = """{"data":{"edition_owned":null}}"""
    assertFailsWith<TrackerException> { connector().markOwned(book) }
    Unit
  }

  @Test
  fun aNewEditionStaysWithItsBook() = runBlocking {
    reply = """{"data":{"insert_edition":{"id":500,"errors":null,"edition":{"id":500,"title":"All Systems Red","isbn_13":"9781250000000"}}}}"""
    val created = connector().createEdition(book.copy(connectorAudioBook = listOf(ConnectorAudioBookEdition("", "9781250000000"))))
    assertEquals("42", created.id)
    assertEquals("500", created.connectorAudioBook.single().id)
  }

  @Test
  fun aRefusedEditionIsAnError() = runBlocking {
    reply = """{"data":{"insert_edition":{"id":null,"errors":["ISBN already taken"],"edition":null}}}"""
    val error = assertFailsWith<TrackerException> { connector().createEdition(book) }
    assertTrue("ISBN already taken" in error.message!!, error.message)
  }
}
