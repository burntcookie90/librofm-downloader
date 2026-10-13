package com.vishnurajeevan.libroabs.tracker

import com.vishnurajeevan.libroabs.audiobookshelf.ListeningProgress
import com.vishnurajeevan.libroabs.audiobookshelf.ListeningProgressSource
import com.vishnurajeevan.libroabs.audiobookshelf.cleanPersonName
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.DryRunTrackerConnector
import com.vishnurajeevan.libroabs.connector.EditionExtras
import com.vishnurajeevan.libroabs.connector.ReadingStatus
import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.connector.TrackerRead
import com.vishnurajeevan.libroabs.connector.TrackerUnavailableException
import com.vishnurajeevan.libroabs.connector.TrackerUserBook
import com.vishnurajeevan.libroabs.db.repo.SyncStateRepo
import com.vishnurajeevan.libroabs.db.repo.TrackerWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.TrackerWishlistSyncStatus
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.WishlistItemSyncStatus
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import com.vishnurajeevan.libroabs.server.ui.HardcoverAction
import com.vishnurajeevan.libroabs.server.ui.HardcoverApplyResult
import com.vishnurajeevan.libroabs.server.ui.HardcoverBookPlan
import com.vishnurajeevan.libroabs.server.ui.HardcoverMatch
import com.vishnurajeevan.libroabs.server.ui.HardcoverOverview
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Keeps Hardcover in step with libro.fm (owned books and wishlist, in the directions [ServerInfo.hardcoverSyncMode]
 * allows) and with Audiobookshelf's listening progress.
 *
 * Everything goes through a plan: the web UI shows one for the user to choose from, and the scheduled sync applies
 * the actions a plan ticks by default. In a dry run Hardcover is read but never changed.
 */
@Inject
@SingleIn(AppScope::class)
class HardcoverSync(
  private val serverInfo: ServerInfo,
  trackerConnector: TrackerConnector?,
  private val libro: LibroShelves,
  private val progressSource: ListeningProgressSource,
  private val trackerWishlistRepo: TrackerWishlistSyncStatusRepo,
  private val dbWriter: DbWriter,
  private val syncState: SyncStateRepo,
  private val logger: Logger,
  private val clock: Clock = Clock.System,
  private val zone: TimeZone = TimeZone.currentSystemDefault(),
) {
  private val tracker: TrackerConnector? =
    trackerConnector?.let { if (serverInfo.dryRun) DryRunTrackerConnector(it) { msg -> logger.i("Hardcover: $msg") } else it }

  val enabled: Boolean get() = tracker != null

  private val plansLock = Mutex()
  private val plans = ArrayDeque<Plan>()

  /** Title searches are slow and rate limited; a book Hardcover lacks is looked up again after a day. */
  private val searches = ConcurrentHashMap<String, Pair<Instant, BookMatch?>>()
  private val libroBooks = ConcurrentHashMap<String, Pair<Instant, Book?>>()

  suspend fun login() {
    tracker?.login()
  }

  /** Works out what a sync would change, for the user to choose from. */
  suspend fun preview(): HardcoverOverview {
    val plan = plan(includeProgress = progressSource.progressAvailable)
    plansLock.withLock {
      plans.removeAll { clock.now() - it.createdAt > PLAN_LIFETIME }
      plans.addLast(plan)
      while (plans.size > 3) plans.removeFirst()
    }
    return plan.overview
  }

  /** Carries out the chosen actions of a preview from [preview]. */
  suspend fun apply(planId: String, keys: Set<String>): HardcoverApplyResult {
    val plan = plansLock.withLock { plans.firstOrNull { it.id == planId && clock.now() - it.createdAt <= PLAN_LIFETIME } }
      ?: return HardcoverApplyResult(0, emptyList(), expired = true)
    return apply(plan, keys)
  }

  /**
   * The scheduled sync: applies what a preview would tick. Listening progress is only sent when
   * [ServerInfo.hardcoverSyncProgress] is on, and only for listening since it was turned on, so turning it on doesn't
   * replay the whole history; earlier progress can be sent from the preview.
   */
  suspend fun syncAutomatically() {
    if (tracker == null) return
    val progressSince = if (serverInfo.hardcoverSyncProgress && progressSource.progressAvailable) progressSyncStart() else null
    val plan = plan(includeProgress = progressSince != null)
    val keys = plan.books.flatMap { book ->
      book.steps.filter { (step, action) ->
        action.selected && (step !is Step.Progress || (step.lastUpdate != null && progressSince != null && step.lastUpdate >= progressSince))
      }.map { it.first.key }
    }.toSet()
    val result = apply(plan, keys)
    plan.overview.errors.forEach { logger.i("Hardcover: $it") }
    if (result.failures.isNotEmpty()) {
      val (title, error) = result.failures.first()
      error("${result.failures.size} Hardcover update(s) failed, first: $title: $error")
    }
  }

  private suspend fun progressSyncStart(): Instant {
    syncState.get(PROGRESS_SINCE_KEY)?.let { stored -> runCatching { Instant.parse(stored) }.getOrNull()?.let { return it } }
    val now = clock.now()
    syncState.put(PROGRESS_SINCE_KEY, now.toString())
    logger.i("Hardcover: sending listening progress from now on; use the Hardcover page to send earlier progress")
    return now
  }

  private suspend fun plan(includeProgress: Boolean): Plan {
    val tracker = tracker ?: error("Hardcover is not configured")
    val mode = serverInfo.hardcoverSyncMode
    val syncOwned = mode in setOf(TrackerSyncMode.LIBRO_OWNED_TO_HARDCOVER, TrackerSyncMode.LIBRO_ALL_TO_HARDCOVER, TrackerSyncMode.ALL)
    val syncWishlist = mode in setOf(TrackerSyncMode.LIBRO_WISHLISTS_TO_HARDCOVER, TrackerSyncMode.LIBRO_ALL_TO_HARDCOVER, TrackerSyncMode.ALL)
    val syncWantToRead = mode in setOf(TrackerSyncMode.HARDCOVER_WANT_TO_READ_TO_LIBRO, TrackerSyncMode.ALL)
    val skip = serverInfo.skipTrackingIsbns.toSet()
    val errors = mutableListOf<String>()

    val library = libro.library().filter { it.isbn !in skip }
    val libraryIsbns = library.map { it.isbn }.toSet()
    val wanted = tracker.getWantedBooks()
    val wantedIsbns = wanted.isbns()
    val ownedIsbns = tracker.getOwnedBooks().isbns()
    val readIsbns = tracker.getReadBooks().isbns()

    val progress: Map<String, ListeningProgress> = if (includeProgress) {
      try {
        val dirs = libro.downloadDirs()
        progressSource.progress(library.map { it to dirs[it.isbn] })
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        errors += "Couldn't read listening progress from Audiobookshelf: ${e.message}"
        emptyMap()
      }
    } else {
      emptyMap()
    }

    val libroWishlist = if (syncWishlist || syncWantToRead) libro.wishlist() else emptyList()
    // Wishlist books already on a Hardcover shelf, sent before, or since bought are left alone.
    val alreadySent = trackerWishlistRepo.getSyncedIsbns().toSet()
    val toWant = if (syncWishlist) {
      libroWishlist.filter { it.isbn !in skip && it.isbn !in libraryIsbns && it.isbn !in alreadySent && it.isbn !in wantedIsbns + ownedIsbns + readIsbns }
    } else {
      emptyList()
    }
    val toOwn = if (syncOwned) library else emptyList()
    val withProgress = library.filter { it.isbn in progress }

    // One lookup by ISBN for every book that might need an action.
    val isbns = (toOwn.map { it.isbn } + toWant.map { it.isbn } + withProgress.map { it.isbn }).distinct()
    val editions = tracker.getEditions(isbns).flatMap { book ->
      book.connectorAudioBook.mapNotNull { edition -> edition.isbn13?.let { it to book.copy(connectorAudioBook = listOf(edition)) } }
    }.toMap()

    val notFound = mutableListOf<String>()
    suspend fun target(isbn: String, title: String, authors: List<String>, details: Book?): Target? {
      editions[isbn]?.let { return Target.Edition(it) }
      val match = try {
        search(isbn, title, authors)
      } catch (e: TrackerUnavailableException) {
        throw e
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        errors += "$title: couldn't search Hardcover: ${e.message}"
        return null
      }
      if (match == null) notFound += title
      return match?.let { Target.NewEdition(it, isbn, details) }
    }

    val targets = mutableMapOf<String, Target?>()
    for (book in (toOwn + withProgress).distinctBy { it.isbn }) {
      targets[book.isbn] = target(book.isbn, book.title, book.authors, book)
    }
    for (book in toWant) targets[book.isbn] = target(book.isbn, book.title, book.authors, null)

    val bookIds = withProgress.mapNotNull { targets[it.isbn]?.bookId }.distinct()
    val userBooks = if (bookIds.isEmpty()) emptyMap() else tracker.getUserBooks(bookIds).associateBy { it.bookId }
    val now = clock.now()

    val planned = mutableListOf<PlannedBook>()
    var upToDate = 0
    fun add(isbn: String, title: String, author: String?, target: Target?, steps: List<Pair<Step, HardcoverAction>>) {
      if (steps.isEmpty()) {
        if (target != null) upToDate++
      } else {
        planned += PlannedBook(isbn, title, author, target, steps)
      }
    }

    for (book in (toOwn + withProgress).distinctBy { it.isbn }) {
      val target = targets[book.isbn] ?: continue
      val steps = buildList {
        if (syncOwned && (target is Target.NewEdition || book.isbn !in ownedIsbns)) {
          add(Step.Owned("owned:${book.isbn}") to HardcoverAction("owned:${book.isbn}", "Mark as owned"))
        }
        progress[book.isbn]?.let { listening ->
          val change = progressChange(listening, userBooks[target.bookId], now, zone) ?: return@let
          val note = if (change.stale) "Last played ${listening.lastUpdate?.toLocalDateTime(zone)?.date?.display() ?: "a while ago"}, so it may have been abandoned" else null
          val key = "progress:${book.isbn}"
          add(
            Step.Progress(key, change.status, change.read, userBooks[target.bookId], listening.lastUpdate) to
              HardcoverAction(key, change.description, selected = !change.stale, note = note)
          )
        }
      }
      add(book.isbn, book.title, book.authors.firstOrNull(), target, steps)
    }
    for (book in toWant) {
      val target = targets[book.isbn] ?: continue
      val key = "wanted:${book.isbn}"
      add(book.isbn, book.title, book.authors.firstOrNull(), target, listOf(Step.Wanted(key) to HardcoverAction(key, "Add to Want to Read")))
    }
    val notOnLibro = mutableListOf<String>()
    if (syncWantToRead) {
      val onWishlist = libroWishlist.map { it.isbn }.toSet()
      val sentBefore = libro.addedFromTracker()
      for (book in wanted) {
        val bookIsbns = book.connectorAudioBook.mapNotNull { it.isbn13 }.distinct()
        // A book counts as bought, wished for or skipped under any of its audiobook editions.
        if (bookIsbns.isEmpty() || bookIsbns.any { it in skip || it in libraryIsbns || it in onWishlist || it in sentBefore }) continue
        // Hardcover lists every audiobook edition; only one of them is wished for, the first libro.fm sells.
        val onLibro = bookIsbns.firstNotNullOfOrNull { libroBook(it) }
        if (onLibro == null) {
          notOnLibro += book.title
          continue
        }
        val key = "libro:${onLibro.isbn}"
        add(onLibro.isbn, onLibro.title, onLibro.authors.firstOrNull(), null, listOf(Step.LibroWishlist(key) to HardcoverAction(key, "Add to your libro.fm wishlist")))
      }
    }

    val id = UUID.randomUUID().toString()
    return Plan(
      id = id,
      createdAt = now,
      books = planned,
      overview = HardcoverOverview(
        planId = id,
        books = planned.sortedBy { it.title.lowercase() }.map { it.view() },
        upToDate = upToDate,
        notFound = notFound.distinct().sortedBy { it.lowercase() },
        errors = errors,
        notOnLibro = notOnLibro.distinct().sortedBy { it.lowercase() },
        progressCompared = includeProgress,
        progressSyncedAutomatically = serverInfo.hardcoverSyncProgress,
      ),
    )
  }

  private suspend fun search(isbn: String, title: String, authors: List<String>): BookMatch? {
    searches[isbn]?.let { (at, match) -> if (clock.now() - at < 1.days) return match }
    val tracker = tracker ?: return null
    val query = listOfNotNull(title, authors.firstOrNull()?.let(::cleanPersonName)).joinToString(" ")
    val match = bestMatch(title, authors, tracker.searchBooks(query))
    searches[isbn] = clock.now() to match
    return match
  }

  /** The libro.fm book with this ISBN, or null when libro.fm doesn't sell it; remembered for a day. */
  private suspend fun libroBook(isbn: String): Book? {
    libroBooks[isbn]?.let { (at, book) -> if (clock.now() - at < 1.days) return book }
    val book = try {
      libro.bookDetails(isbn)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      logger.v("Hardcover: libro.fm has no book $isbn: ${e.message}")
      null
    }
    libroBooks[isbn] = clock.now() to book
    return book
  }

  private suspend fun apply(plan: Plan, keys: Set<String>): HardcoverApplyResult {
    val tracker = tracker ?: return HardcoverApplyResult(0, listOf("Hardcover" to "not configured"))
    var applied = 0
    val failures = mutableListOf<Pair<String, String>>()
    for (book in plan.books) {
      val steps = book.steps.map { it.first }.filter { it.key in keys }
      if (steps.isEmpty()) continue
      try {
        if (apply(tracker, book, steps)) applied++
      } catch (e: CancellationException) {
        throw e
      } catch (e: TrackerUnavailableException) {
        failures += book.title to (e.message ?: "Hardcover is unavailable")
        logger.i("Hardcover: ${e.message}")
        break
      } catch (e: Exception) {
        failures += book.title to (e.message ?: e::class.simpleName.orEmpty())
        logger.i("Hardcover: ${book.title}: ${e.message}")
      }
    }
    return HardcoverApplyResult(applied, failures)
  }

  /** Returns whether anything was done (a dry run does nothing). */
  private suspend fun apply(tracker: TrackerConnector, book: PlannedBook, steps: List<Step>): Boolean {
    val hardcoverBook: ConnectorBook? = when (val target = book.target) {
      is Target.Edition -> target.book
      is Target.NewEdition -> {
        val details = target.details ?: libro.bookDetails(book.isbn)
        tracker.createEdition(
          ConnectorBook(
            id = target.match.candidate.id,
            title = details.title,
            releaseDate = details.publication_date.toLocalDateTime(TimeZone.UTC).date,
            contributions = target.match.candidate.authors,
            connectorAudioBook = listOf(ConnectorAudioBookEdition(id = "", isbn13 = book.isbn)),
          ),
          EditionExtras(
            audioSeconds = details.audiobook_info.duration.takeIf { it > 0 },
            narrators = details.audiobook_info.narrators.map(::cleanPersonName).filter { it.isNotEmpty() },
            publisher = details.publisher.takeIf { it.isNotBlank() },
          ),
        ) ?: return false
      }
      null -> null
    }
    for (step in steps) {
      when (step) {
        is Step.Owned -> tracker.markOwned(hardcoverBook ?: continue)
        is Step.Wanted -> {
          tracker.markWanted(hardcoverBook ?: continue)
          dbWriter.write(TrackerWishlistSyncStatus(book.isbn, WishlistItemSyncStatus.SUCCESS))
        }
        is Step.Progress -> applyProgress(tracker, hardcoverBook ?: continue, step)
        is Step.LibroWishlist -> if (serverInfo.dryRun) {
          logger.i("Hardcover: Dry run: would add ${book.title} to the libro.fm wishlist")
        } else {
          libro.addToWishlist(listOf(book.isbn))
        }
      }
    }
    return !serverInfo.dryRun
  }

  private suspend fun applyProgress(tracker: TrackerConnector, book: ConnectorBook, step: Step.Progress) {
    val editionId = book.connectorAudioBook.first().id
    var userBookId = step.userBook?.id
    if (step.status != null || userBookId == null) {
      userBookId = tracker.setStatus(book.id, editionId, step.status ?: ReadingStatus.CURRENTLY_READING, userBookId)
    }
    var read = step.read?.copy(editionId = editionId) ?: return
    if (read.id == null && step.status != null) {
      // Changing the status can start a read on Hardcover's side; fill that one in rather than adding a second.
      val known = step.userBook?.reads.orEmpty().mapNotNull { it.id }.toSet()
      tracker.getUserBooks(listOf(book.id)).firstOrNull { it.id == userBookId }
        ?.reads?.firstOrNull { it.id != null && it.id !in known }
        ?.let { started -> read = read.copy(id = started.id, startedAt = read.startedAt ?: started.startedAt) }
    }
    tracker.saveRead(userBookId, read)
  }

  private fun List<ConnectorBook>.isbns(): Set<String> = flatMap { book -> book.connectorAudioBook.mapNotNull { it.isbn13 } }.toSet()

  private sealed interface Target {
    val bookId: String

    /** Hardcover already has this audiobook edition. */
    data class Edition(val book: ConnectorBook) : Target {
      override val bookId: String get() = book.id
    }

    /** Hardcover has the book but not this audiobook edition; applying adds it. [details] is fetched then if `null`. */
    data class NewEdition(val match: BookMatch, val isbn: String, val details: Book?) : Target {
      override val bookId: String get() = match.candidate.id
    }
  }

  private sealed interface Step {
    val key: String

    data class Owned(override val key: String) : Step
    data class Wanted(override val key: String) : Step
    data class LibroWishlist(override val key: String) : Step
    data class Progress(
      override val key: String,
      val status: ReadingStatus?,
      val read: TrackerRead?,
      val userBook: TrackerUserBook?,
      val lastUpdate: Instant?,
    ) : Step
  }

  private class PlannedBook(
    val isbn: String,
    val title: String,
    val author: String?,
    val target: Target?,
    val steps: List<Pair<Step, HardcoverAction>>,
  ) {
    fun view() = HardcoverBookPlan(
      isbn = isbn,
      title = title,
      author = author,
      match = when (target) {
        is Target.Edition -> HardcoverMatch(target.book.title, null, "ISBN", createsEdition = false)
        is Target.NewEdition -> HardcoverMatch(target.match.candidate.title, target.match.candidate.url, target.match.reason, createsEdition = true)
        null -> null
      },
      actions = steps.map { it.second },
    )
  }

  private class Plan(val id: String, val createdAt: Instant, val books: List<PlannedBook>, val overview: HardcoverOverview)

  private companion object {
    const val PROGRESS_SINCE_KEY = "hardcover_progress_since"
    val PLAN_LIFETIME = 1.hours
  }
}
