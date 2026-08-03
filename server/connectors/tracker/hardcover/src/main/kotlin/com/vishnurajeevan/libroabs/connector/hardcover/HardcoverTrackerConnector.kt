package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.adapter.datetime.KotlinxLocalDateAdapter
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.vishnurajeevan.hardcover.*
import com.vishnurajeevan.hardcover.type.ContributionInputType
import com.vishnurajeevan.hardcover.type.Date
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.ConnectorContributor
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.graph.Named
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.*
import kotlin.time.Duration.Companion.milliseconds

@AssistedInject
class HardcoverTrackerConnector(
  @Assisted @Named("hardcover-token") token: String,
  @Assisted @Named("hardcover-endpoint") endpoint: String,
  private val logger: (String) -> Unit = {},
  @Io private val dispatcher: CoroutineDispatcher,
) : TrackerConnector {

  private val apolloClient: ApolloClient = ApolloClient.Builder()
    .serverUrl(endpoint)
    .addHttpInterceptor(RateLimitInterceptor(minInterval = MIN_REQUEST_INTERVAL, logger = logger))
    .addHttpInterceptor(AuthorizationInterceptor(token))
    .addInterceptor(LoggingInterceptor(logger))
    .addCustomScalarAdapter(
      customScalarType = Date.type,
      customScalarAdapter = KotlinxLocalDateAdapter
    )
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

  override suspend fun getReadBooks(): List<ConnectorBook> {
    return apolloClient.query(MyReadQuery()).execute().data?.me?.flatMap { me ->
      me.user_books.map { userBook ->
        userBook.book.let {
          ConnectorBook(
            id = it.id.toString(),
            title = it.title!!,
            connectorAudioBook = it.editions.mapNotNull { edition ->
              edition.reading_format?.let {
                ConnectorAudioBookEdition(
                  id = it.toString(),
                  isbn13 = edition.isbn_13
                )
              }
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

  /**
   * Looks editions up in batches rather than passing an entire library into a single `_in` filter.
   * A 2000 book library previously produced a 2000 element `_in` on their side, twice per sync.
   */
  override suspend fun getEditions(isbn13s: List<String>): List<ConnectorBook> {
    return isbn13s.chunked(ISBN_LOOKUP_CHUNK_SIZE).flatMap { chunk ->
      apolloClient.query(
        query = GetEditionByIsbnsQuery(chunk)
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
  }

  override suspend fun createEdition(book: ConnectorBook): ConnectorBook? {
    return apolloClient.mutation(
      CreateEditionMutation(
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

  override suspend fun markWanted(book: ConnectorBook) {
    apolloClient.mutation(
      mutation = AddBookMutation(book.id.toInt())
    )
      .execute()
  }

  override suspend fun markOwned(book: ConnectorBook) {
    apolloClient.mutation(
      mutation = MarkEditionAsOwnedMutation(
        id = book.connectorAudioBook.first().id.toInt()
      )
    )
      .execute()
  }

  /**
   * Finds a book via Hardcover's search endpoint, then verifies the candidate locally.
   *
   * This previously filtered the `books` table directly with `title: {_eq: $title}` joined through
   * `contributions.author.name`. That is an ad-hoc query against their primary table for something
   * they expose a purpose-built search index for, and exact equality meant it missed almost every
   * real-world title variation — subtitles, punctuation, case, `&` vs `and`, author initials.
   *
   * Search does the recall work; [BookMatching] does the precision work. The verification step is not
   * optional: search is fuzzy and ranked, and an unverified top hit would attach a Libro.fm audiobook
   * edition to whatever book happened to rank first.
   */
  override suspend fun searchByTitle(title: String, author: String): ConnectorBook? {
    val ids = apolloClient.query(SearchQuery(query = "$title $author", perPage = SEARCH_RESULT_LIMIT))
      .execute()
      .data
      ?.search
      ?.ids
      ?.filterNotNull()
      .orEmpty()

    if (ids.isEmpty()) return null

    val candidates = apolloClient.query(BooksByIdsQuery(ids))
      .execute()
      .data
      ?.books
      .orEmpty()
      // `books` does not preserve the ranking that search returned, so restore it before matching.
      .sortedBy { ids.indexOf(it.id) }

    val matched = BookMatching.bestMatch(
      title = title,
      author = author,
      candidates = candidates,
      titlesOf = { book ->
        listOfNotNull(book.title) + BookMatching.extractTitles(book.alternative_titles)
      },
      authorsOf = { book -> book.contributions.mapNotNull { it.author?.name } },
    ) ?: return null

    return ConnectorBook(
      id = matched.id.toString(),
      title = matched.title.orEmpty(),
      contributions = matched.contributions.mapNotNull { contribution ->
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

  @AssistedFactory
  interface Factory {
    fun create(
      @Named("hardcover-token") token: String,
      @Named("hardcover-endpoint") endpoint: String,
    ): HardcoverTrackerConnector
  }

  private companion object {
    /** ~40 requests/min, comfortably under Hardcover's published limit. */
    val MIN_REQUEST_INTERVAL = 1500.milliseconds
    const val ISBN_LOOKUP_CHUNK_SIZE = 100

    /** Enough ranked candidates to survive a near miss at the top, few enough to stay cheap. */
    const val SEARCH_RESULT_LIMIT = 5
  }
}