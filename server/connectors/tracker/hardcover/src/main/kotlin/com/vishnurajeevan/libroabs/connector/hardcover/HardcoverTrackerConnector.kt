package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.adapter.datetime.KotlinxLocalDateAdapter
import com.apollographql.apollo.ApolloCall
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloException
import com.apollographql.apollo.exception.ApolloHttpException
import com.vishnurajeevan.hardcover.*
import com.vishnurajeevan.hardcover.type.ContributionInputType
import com.vishnurajeevan.hardcover.type.Date
import com.vishnurajeevan.hardcover.type.DatesReadInput
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.EditionExtras
import com.vishnurajeevan.libroabs.connector.ReadingStatus
import com.vishnurajeevan.libroabs.connector.TrackerCandidate
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.connector.TrackerException
import com.vishnurajeevan.libroabs.connector.TrackerPermissionException
import com.vishnurajeevan.libroabs.connector.TrackerRead
import com.vishnurajeevan.libroabs.connector.TrackerUnavailableException
import com.vishnurajeevan.libroabs.connector.TrackerUserBook
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@AssistedInject
class HardcoverTrackerConnector(
  @Assisted @Named("hardcover-token") token: String,
  @Assisted @Named("hardcover-endpoint") endpoint: String,
  private val logger: Logger,
  @Io private val dispatcher: CoroutineDispatcher,
) : TrackerConnector {

  private val apolloClient: ApolloClient = ApolloClient.Builder()
    .serverUrl(endpoint)
    .addHttpInterceptor(AuthorizationInterceptor(token))
    .addHttpInterceptor(RateLimitInterceptor())
    .addInterceptor(LoggingInterceptor(logger::v))
    .addCustomScalarAdapter(
      customScalarType = Date.type,
      customScalarAdapter = KotlinxLocalDateAdapter
    )
    .dispatcher(dispatcher)
    .build()

  private val userLock = Mutex()
  private var user: MyIdQuery.Me? = null

  private suspend fun currentUser(): MyIdQuery.Me = userLock.withLock {
    user ?: (apolloClient.query(MyIdQuery()).data().me.firstOrNull()
      ?: throw TrackerUnavailableException("Hardcover didn't return an account for HARDCOVER_TOKEN"))
      .also { user = it }
  }

  override suspend fun login() {
    logger.i("Logged in to Hardcover as ${currentUser().username}")
  }

  override suspend fun getWantedBooks(): List<ConnectorBook> =
    apolloClient.query(MyWantToReadQuery()).data().me.flatMap { me ->
      me.user_books.map { userBook ->
        val book = userBook.book
        ConnectorBook(
          id = book.id.toString(),
          title = book.title.orEmpty(),
          // The edition the user picked, if it's an audiobook, comes first.
          connectorAudioBook = book.editions.sortedByDescending { it.id == userBook.edition_id }
            .map { ConnectorAudioBookEdition(id = it.id.toString(), isbn13 = it.isbn_13) }
        )
      }
    }

  override suspend fun getReadBooks(): List<ConnectorBook> =
    apolloClient.query(MyReadQuery()).data().me.flatMap { me ->
      me.user_books.map { userBook ->
        val book = userBook.book
        ConnectorBook(
          id = book.id.toString(),
          title = book.title.orEmpty(),
          connectorAudioBook = book.editions.map { ConnectorAudioBookEdition(id = it.id.toString(), isbn13 = it.isbn_13) }
        )
      }
    }

  override suspend fun getOwnedBooks(): List<ConnectorBook> =
    apolloClient.query(MyOwnedQuery(currentUser().id)).data().list_books.mapNotNull { listBook ->
      val edition = listBook.edition ?: return@mapNotNull null
      ConnectorBook(
        id = listBook.id.toString(),
        title = listBook.book.title.orEmpty(),
        connectorAudioBook = listOf(ConnectorAudioBookEdition(id = edition.id.toString(), isbn13 = edition.isbn_13))
      )
    }

  override suspend fun getEditions(isbn13s: List<String>): List<ConnectorBook> {
    if (isbn13s.isEmpty()) return emptyList()
    return apolloClient.query(GetEditionByIsbnsQuery(isbn13s)).data().books.map { book ->
      ConnectorBook(
        id = book.id.toString(),
        title = book.title.orEmpty(),
        connectorAudioBook = book.editions.map { ConnectorAudioBookEdition(id = it.id.toString(), isbn13 = it.isbn_13) }
      )
    }
  }

  override suspend fun searchBooks(query: String): List<TrackerCandidate> {
    val results = apolloClient.query(SearchBooksQuery(query)).data().search?.results
    return searchCandidates(results)
  }

  override suspend fun createEdition(book: ConnectorBook, extras: EditionExtras): ConnectorBook {
    val isbn = book.connectorAudioBook.firstNotNullOfOrNull { it.isbn13 }
      ?: throw TrackerException("Can't create a Hardcover edition of ${book.title} without an ISBN")
    val known = if (extras.narrators.isEmpty() && extras.publisher == null) {
      null
    } else {
      apolloClient.query(ContributorIdsQuery(extras.narrators, listOfNotNull(extras.publisher))).data()
    }
    // Narrators Hardcover doesn't know yet are left out rather than added as new people.
    val narrators = extras.narrators.mapNotNull { name -> known?.authors?.firstOrNull { it.name == name }?.id }.distinct()
    val result = try {
      apolloClient.mutation(
        CreateEditionMutation(
          book_id = book.id.toInt(),
          title = book.title,
          isbn_13 = isbn,
          release_date = book.releaseDate,
          contributions = book.contributions.map {
            ContributionInputType(author_id = it.id.toInt(), contribution = Optional.present("Author"))
          } + narrators.map {
            ContributionInputType(author_id = it, contribution = Optional.present("Narrator"))
          },
          audio_seconds = extras.audioSeconds,
          // Publisher ids are bigints, which come back untyped.
          publisher_id = known?.publishers?.firstOrNull()?.id?.toString()?.toIntOrNull(),
        )
      ).data()
    } catch (e: TrackerException) {
      // Adding editions by hand is a supporter and librarian perk; Hardcover only says so in the error's text.
      if (e !is TrackerUnavailableException && "only supporters" in e.message.orEmpty().lowercase()) {
        throw TrackerPermissionException("Hardcover only lets supporters and librarians add editions", e)
      }
      throw e
    }.insert_edition
    val problems = result?.errors?.filterNotNull().orEmpty()
    val edition = result?.edition
    if (edition == null || problems.isNotEmpty()) {
      throw TrackerException(
        "Hardcover wouldn't create an audiobook edition of ${book.title}: ${problems.joinToString("; ").ifEmpty { "no reason given" }}"
      )
    }
    // The edition belongs to the book it was created for; keep that book's id so it can be added to a list.
    return book.copy(
      title = edition.title ?: book.title,
      connectorAudioBook = listOf(ConnectorAudioBookEdition(id = edition.id.toString(), isbn13 = edition.isbn_13))
    )
  }

  override suspend fun markWanted(book: ConnectorBook) {
    val result = apolloClient.mutation(
      InsertUserBookMutation(
        bookId = book.id.toInt(),
        editionId = book.connectorAudioBook.firstNotNullOfOrNull { it.id.toIntOrNull() },
        statusId = ReadingStatus.WANT_TO_READ.hardcoverId,
      )
    ).data().insert_user_book
    if (result?.id == null) {
      throw TrackerException("Hardcover didn't add ${book.title} to Want to Read: ${result?.error ?: "no reason given"}")
    }
  }

  override suspend fun markOwned(book: ConnectorBook) {
    val editionId = book.connectorAudioBook.firstNotNullOfOrNull { it.id.toIntOrNull() }
      ?: throw TrackerException("Can't mark ${book.title} as owned on Hardcover without an edition")
    val result = apolloClient.mutation(MarkEditionAsOwnedMutation(id = editionId)).data().edition_owned
    if (result?.id == null) throw TrackerException("Hardcover didn't mark ${book.title} as owned")
  }

  override suspend fun getUserBooks(bookIds: List<String>): List<TrackerUserBook> {
    val ids = bookIds.mapNotNull { it.toIntOrNull() }
    if (ids.isEmpty()) return emptyList()
    return apolloClient.query(MyUserBooksQuery(ids)).data().me.flatMap { me ->
      me.user_books.map { userBook ->
        TrackerUserBook(
          id = userBook.id.toString(),
          bookId = userBook.book_id.toString(),
          status = ReadingStatus.entries.firstOrNull { it.hardcoverId == userBook.status_id },
          editionId = userBook.edition_id?.toString(),
          reads = userBook.user_book_reads.map { read ->
            TrackerRead(
              id = read.id.toString(),
              startedAt = read.started_at,
              finishedAt = read.finished_at,
              progressSeconds = read.progress_seconds,
              editionId = read.edition_id?.toString(),
            )
          },
        )
      }
    }
  }

  override suspend fun setStatus(bookId: String, editionId: String, status: ReadingStatus, userBookId: String?): String {
    val (id, error) = if (userBookId == null) {
      apolloClient.mutation(InsertUserBookMutation(bookId.toInt(), editionId.toInt(), status.hardcoverId)).data()
        .insert_user_book.let { it?.id to it?.error }
    } else {
      apolloClient.mutation(UpdateUserBookMutation(userBookId.toInt(), editionId.toInt(), status.hardcoverId)).data()
        .update_user_book.let { it?.id to it?.error }
    }
    return id?.toString() ?: throw TrackerException("Hardcover didn't set book $bookId to $status: ${error ?: "no reason given"}")
  }

  override suspend fun saveRead(userBookId: String, read: TrackerRead) {
    val input = DatesReadInput(
      started_at = Optional.presentIfNotNull(read.startedAt),
      finished_at = Optional.presentIfNotNull(read.finishedAt),
      progress_seconds = Optional.presentIfNotNull(read.progressSeconds),
      edition_id = Optional.presentIfNotNull(read.editionId?.toIntOrNull()),
    )
    val readId = read.id
    val (id, error) = if (readId == null) {
      apolloClient.mutation(InsertReadMutation(userBookId.toInt(), input)).data().insert_user_book_read.let { it?.id to it?.error }
    } else {
      apolloClient.mutation(UpdateReadMutation(readId.toInt(), input)).data().update_user_book_read.let { it?.id to it?.error }
    }
    if (id == null) throw TrackerException("Hardcover didn't save the read: ${error ?: "no reason given"}")
  }

  @AssistedFactory
  interface Factory {
    fun create(
      @Named("hardcover-token") token: String,
      @Named("hardcover-endpoint") endpoint: String,
    ): HardcoverTrackerConnector
  }
}

/** The response's data, or a [TrackerException] saying why there is none. */
internal suspend fun <D : Operation.Data> ApolloCall<D>.data(): D {
  val response = execute()
  val name = response.operation.name()
  response.exception?.let { throw it.toTrackerException(name) }
  response.errors?.takeIf { it.isNotEmpty() }?.let { errors ->
    throw TrackerException("Hardcover rejected $name: ${errors.joinToString("; ") { it.message }}")
  }
  return response.data ?: throw TrackerException("Hardcover sent no data for $name")
}

private fun ApolloException.toTrackerException(operation: String): TrackerException =
  when ((this as? ApolloHttpException)?.statusCode) {
    401, 403 -> TrackerUnavailableException(
      "Hardcover rejected HARDCOVER_TOKEN (HTTP ${(this as ApolloHttpException).statusCode}). " +
        "Copy a current token from https://hardcover.app/account/api",
      this,
    )
    429 -> TrackerUnavailableException(
      "Hardcover's rate limit was reached (60 requests a minute, 5,000 a day); the rest will sync next time",
      this,
    )
    else -> TrackerException("Hardcover request $operation failed: ${message ?: this::class.simpleName}", this)
  }

/** Hardcover's ids for the reading statuses. */
internal val ReadingStatus.hardcoverId: Int
  get() = when (this) {
    ReadingStatus.WANT_TO_READ -> 1
    ReadingStatus.CURRENTLY_READING -> 2
    ReadingStatus.READ -> 3
    ReadingStatus.PAUSED -> 4
    ReadingStatus.DID_NOT_FINISH -> 5
    ReadingStatus.IGNORED -> 6
  }
