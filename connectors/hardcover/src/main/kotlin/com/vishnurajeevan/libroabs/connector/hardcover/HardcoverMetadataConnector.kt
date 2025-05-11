package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.apollo.ApolloClient
import com.vishnurajeevan.hardcover.MyWantToReadQuery
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBook
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.MetadataConnector
import kotlinx.coroutines.CoroutineDispatcher
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

  override suspend fun getWantedBooks(): List<ConnectorBook> = withContext(dispatcher) {
    apolloClient.query(MyWantToReadQuery()).execute().data?.me?.flatMap { me ->
      me.user_books.map { userBook ->
        userBook.book.let {
          ConnectorBook(
            id = it.id.toString(),
            name = it.title!!,
            connectorAudioBook = it.default_audio_edition?.let {
              ConnectorAudioBook(
                id = it.id.toString(),
                isbn13 = it.isbn_13
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