package com.vishnurajeevan.libroabs.connector.hardcover

import com.sun.net.httpserver.HttpServer
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.ConnectorContributor
import com.vishnurajeevan.libroabs.connector.EditionExtras
import com.vishnurajeevan.libroabs.connector.ReadingStatus
import com.vishnurajeevan.libroabs.connector.TrackerRead
import com.vishnurajeevan.libroabs.connector.TrackerException
import com.vishnurajeevan.libroabs.connector.TrackerUnavailableException
import com.vishnurajeevan.libroabs.models.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
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
  /** Replies for successive requests; [reply] once these run out. */
  private var replies = ArrayDeque<String>()

  private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
    createContext("/graphql") { exchange ->
      requests += exchange.requestHeaders.getFirst("Authorization") to exchange.requestBody.readBytes().decodeToString()
      val body = (replies.removeFirstOrNull() ?: reply).toByteArray()
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

  @Test
  fun searchResultsKeepOnlyAuthors() = runBlocking {
    reply = """{"data":{"search":{"results":{"hits":[{"document":{"id":"427537","title":"Artificial Condition",
      "alternative_titles":["Murderbot 2"],"compilation":false,"users_count":4920,"release_year":2018,"slug":"artificial-condition",
      "contributions":[{"author":{"id":168073,"name":"Martha Wells"},"contribution":"Author"},
                       {"author":{"id":9,"name":"Some Translator"},"contribution":"Translator"}]}}]}}}}"""
    val candidate = connector().searchBooks("Artificial Condition Martha Wells").single()
    assertEquals("427537", candidate.id)
    assertEquals(listOf("Murderbot 2"), candidate.alternativeTitles)
    assertEquals(listOf(ConnectorContributor("168073", "Martha Wells")), candidate.authors)
    assertEquals("https://hardcover.app/books/artificial-condition", candidate.url)
  }

  @Test
  fun editionsNameNarratorsHardcoverKnowsAndTheirRoles() = runBlocking {
    replies = ArrayDeque(listOf(
      """{"data":{"authors":[{"id":261401,"name":"Kevin R. Free"}],"publishers":[{"id":77,"name":"Recorded Books"}]}}""",
      """{"data":{"insert_edition":{"id":500,"errors":null,"edition":{"id":500,"title":"All Systems Red","isbn_13":"9781250000000"}}}}""",
    ))
    connector().createEdition(
      book.copy(contributions = listOf(ConnectorContributor("168073", "Martha Wells"))),
      EditionExtras(audioSeconds = 11_865, narrators = listOf("Kevin R. Free", "Unknown Person"), publisher = "Recorded Books"),
    )
    val mutation = requests.last().second
    assertTrue(""""author_id":168073,"contribution":"Author"""" in mutation, mutation)
    assertTrue(""""author_id":261401,"contribution":"Narrator"""" in mutation, mutation)
    assertTrue(""""audio_seconds":11865""" in mutation && """"publisher_id":77""" in mutation, mutation)
  }

  @Test
  fun readsComeBackNewestFirstWithTheirStatus() = runBlocking {
    reply = """{"data":{"me":[{"user_books":[{"id":5,"book_id":42,"status_id":2,"edition_id":7,
      "user_book_reads":[{"id":9,"started_at":"2026-09-01","finished_at":null,"progress_seconds":1800,"edition_id":7}]}]}]}}"""
    val userBook = connector().getUserBooks(listOf("42")).single()
    assertEquals(ReadingStatus.CURRENTLY_READING, userBook.status)
    assertEquals(TrackerRead("9", LocalDate(2026, 9, 1), null, 1800, "7"), userBook.reads.single())
  }

  @Test
  fun aNewReadSendsOnlyWhatIsKnown() = runBlocking {
    reply = """{"data":{"insert_user_book_read":{"id":10,"error":null}}}"""
    connector().saveRead("5", TrackerRead(null, LocalDate(2026, 9, 1), null, 1800, "7"))
    val body = requests.single().second
    assertTrue(""""userBookId":5""" in body && """"started_at":"2026-09-01"""" in body && """"progress_seconds":1800""" in body, body)
    assertTrue("finished_at" !in body, body)
  }

  @Test
  fun settingAStatusUpdatesAnExistingEntry() = runBlocking {
    reply = """{"data":{"update_user_book":{"id":5,"error":null}}}"""
    assertEquals("5", connector().setStatus("42", "7", ReadingStatus.READ, "5"))
    assertTrue(""""statusId":3""" in requests.single().second, requests.single().second)
  }
}
