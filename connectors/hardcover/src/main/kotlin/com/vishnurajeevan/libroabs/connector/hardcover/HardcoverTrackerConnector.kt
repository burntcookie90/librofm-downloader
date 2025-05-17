package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.apollo.ApolloClient
import com.vishnurajeevan.hardcover.GetEditionByIsbnsQuery
import com.vishnurajeevan.hardcover.MarkEditionAsOwnedMutation
import com.vishnurajeevan.hardcover.MyIdQuery
import com.vishnurajeevan.hardcover.MyOwnedQuery
import com.vishnurajeevan.hardcover.MyWantToReadQuery
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

class HardcoverTrackerConnector(
  token: String,
  private val logger: (String) -> Unit = {},
  private val dispatcher: CoroutineDispatcher,
) : TrackerConnector {

  private val apolloClient = ApolloClient.Builder()
    .serverUrl("https://api.hardcover.app/v1/graphql")
    .addHttpInterceptor(AuthorizationInterceptor(token))
    .addInterceptor(LoggingInterceptor(logger))
    .dispatcher(dispatcher)
    .build()

  private val myId: Deferred<Int> = CoroutineScope(dispatcher)
    .async(
      start = CoroutineStart.LAZY
    ) {
      apolloClient.query(MyIdQuery())
        .execute()
        .data!!.me.first().id
    }

  override suspend fun getWantedBooks(): List<ConnectorBook> {
    return apolloClient.query(MyWantToReadQuery()).execute().data?.me?.flatMap { me ->
      me.user_books.map { userBook ->
        userBook.book.let {
          ConnectorBook(
            id = it.id.toString(),
            name = it.title!!,
            connectorAudioBook = it.editions.map { edition ->
              ConnectorAudioBookEdition(
                id = edition.reading_format!!.id.toString(),
                isbn13 = edition.isbn_13
              )
            }
          )
        }
      }
    } ?: emptyList()
  }

  override suspend fun getOwnedBooks(): List<ConnectorBook> {
    return apolloClient.query(
      query = MyOwnedQuery(myId.await())
    ).execute()
      .data
      ?.list_books
      ?.map { book ->
        ConnectorBook(
          id = book.id.toString(),
          name = book.book.title!!,
          connectorAudioBook = listOf(book.edition.let { edition ->
            ConnectorAudioBookEdition(
              id = edition!!.id.toString(),
              isbn13 = edition.isbn_13
            )
          })
        )
      } ?: emptyList()
  }

  override suspend fun getEditions(isbn13s: List<String>): List<ConnectorBook> {
    return apolloClient.query(
      query = GetEditionByIsbnsQuery(isbn13s)
    ).execute()
      .data
      ?.books
      ?.map { book ->
        ConnectorBook(
          id = book.id.toString(),
          name = book.title!!,
          connectorAudioBook = book.editions.map { edition ->
            ConnectorAudioBookEdition(
              id = edition.id.toString(),
              isbn13 = edition.isbn_13
            )
          }
        )
      } ?: emptyList()
  }

  override suspend fun markWanted(bookId: String) {
    TODO("Not yet implemented")
  }

  override suspend fun markOwned(book: ConnectorBook) {
    apolloClient.mutation(
      mutation = MarkEditionAsOwnedMutation(
        id = book.connectorAudioBook.first().id.toInt()
      )
    )
      .execute()
  }
}