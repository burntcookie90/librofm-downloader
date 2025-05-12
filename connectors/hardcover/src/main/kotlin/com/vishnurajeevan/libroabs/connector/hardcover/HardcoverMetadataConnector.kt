package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.apollo.ApolloClient
import com.vishnurajeevan.hardcover.MyIdQuery
import com.vishnurajeevan.hardcover.MyWantToReadQuery
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.MetadataConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HardcoverMetadataConnector(
  token: String,
  private val dispatcher: CoroutineDispatcher,
) : MetadataConnector {

  private val apolloClient = ApolloClient.Builder()
    .serverUrl("https://api.hardcover.app/v1/graphql")
    .addHttpInterceptor(AuthorizationInterceptor(token))
    .addInterceptor(LoggingInterceptor())
    .dispatcher(dispatcher)
    .build()

  private var myId: Int? = null

  init {
    CoroutineScope(dispatcher).launch {
      myId = apolloClient.query(MyIdQuery()).execute().data!!.me.first().id
    }
  }

  override suspend fun getWantedBooks(): List<ConnectorBook> = withContext(dispatcher) {
    apolloClient.query(MyWantToReadQuery()).execute().data?.me?.flatMap { me ->
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

  override suspend fun markWanted(bookId: String) {
    TODO("Not yet implemented")
  }

  override suspend fun markOwned(bookId: String) {
    TODO("Not yet implemented")
  }
}