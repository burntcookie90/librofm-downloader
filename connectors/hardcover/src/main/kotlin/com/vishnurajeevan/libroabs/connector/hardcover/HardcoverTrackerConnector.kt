package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.vishnurajeevan.hardcover.BooksQuery
import com.vishnurajeevan.hardcover.CreateEditionMutation
import com.vishnurajeevan.hardcover.GetEditionByIsbnsQuery
import com.vishnurajeevan.hardcover.MarkEditionAsOwnedMutation
import com.vishnurajeevan.hardcover.MyIdQuery
import com.vishnurajeevan.hardcover.MyOwnedQuery
import com.vishnurajeevan.hardcover.MyWantToReadQuery
import com.vishnurajeevan.hardcover.type.ContributionInputType
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.ConnectorContributor
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

class HardcoverTrackerConnector(
    token: String,
    endpoint: String,
    private val logger: (String) -> Unit = {},
    private val dispatcher: CoroutineDispatcher,
) : TrackerConnector {

    private val apolloClient = ApolloClient.Builder()
        .serverUrl(endpoint)
        .addHttpInterceptor(AuthorizationInterceptor(token))
        .addInterceptor(LoggingInterceptor(logger))
        .dispatcher(dispatcher)
        .build()

    private val currentUser: Deferred<MyIdQuery.Me> = CoroutineScope(dispatcher)
        .async(
            start = CoroutineStart.LAZY
        ) {
            apolloClient.query(MyIdQuery())
                .execute()
                .data!!
                .me
                .first()
        }

    override suspend fun login() {
        logger("Logged in as ${currentUser.await().username}")
    }

    override suspend fun getWantedBooks(): List<ConnectorBook> {
        return apolloClient.query(MyWantToReadQuery()).execute().data?.me?.flatMap { me ->
            me.user_books.map { userBook ->
                userBook.book.let {
                    ConnectorBook(
                        id = it.id.toString(),
                        title = it.title!!,
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
            query = MyOwnedQuery(currentUser.await().id)
        ).execute()
            .data
            ?.list_books
            ?.map { book ->
                ConnectorBook(
                    id = book.id.toString(),
                    title = book.book.title!!,
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
                    title = book.title!!,
                    connectorAudioBook = book.editions.map { edition ->
                        ConnectorAudioBookEdition(
                            id = edition.id.toString(),
                            isbn13 = edition.isbn_13
                        )
                    }
                )
            } ?: emptyList()
    }

    override suspend fun createEdition(book: ConnectorBook): ConnectorBook? {
        return apolloClient.mutation(CreateEditionMutation(
            book_id = book.id.toInt(),
            title = book.title,
            isbn_13 = book.connectorAudioBook.first().isbn13!!,
            release_date = book.releaseDate,
            contributions = book.contributions.map {
                ContributionInputType(
                    author_id = it.id.toInt(),
                    contribution = Optional.present(it.name)
                )
            }
        ))
            .execute()
            .data!!
            .insert_edition
            ?.let { book ->
                ConnectorBook(
                    id = book.id.toString(),
                    title = book.edition!!.title!!,
                    connectorAudioBook = listOf(book.edition.let {
                        ConnectorAudioBookEdition(
                            id = it.id.toString(),
                            isbn13 = it.isbn_13
                        )
                    })
                )
            }

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

    override suspend fun searchByTitle(title: String, author: String): ConnectorBook? {
        return apolloClient.query(BooksQuery(title, author))
            .execute()
            .data!!
            .books
            .firstNotNullOfOrNull {
                it.title?.let { name ->
                    ConnectorBook(
                        id = it.id.toString(),
                        title = name,
                        contributions = it.contributions.mapNotNull { contribution ->
                            contribution.author?.name?.let { authorName ->
                                ConnectorContributor(
                                    contribution.author.id.toString(),
                                    authorName
                                )
                            }
                        },
                        connectorAudioBook = emptyList()
                    )
                }
            }
    }
}