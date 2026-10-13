package com.vishnurajeevan.libroabs.tracker

import com.vishnurajeevan.libroabs.audiobookshelf.ListeningProgress
import com.vishnurajeevan.libroabs.connector.ConnectorAudioBookEdition
import com.vishnurajeevan.libroabs.connector.ConnectorBook
import com.vishnurajeevan.libroabs.connector.ConnectorContributor
import com.vishnurajeevan.libroabs.connector.ReadingStatus
import com.vishnurajeevan.libroabs.connector.TrackerCandidate
import com.vishnurajeevan.libroabs.connector.TrackerException
import com.vishnurajeevan.libroabs.connector.TrackerUnavailableException
import com.vishnurajeevan.libroabs.connector.TrackerUserBook
import com.vishnurajeevan.libroabs.db.writer.TrackerWishlistSyncStatus
import com.vishnurajeevan.libroabs.download.FakeWishlist
import com.vishnurajeevan.libroabs.download.FakeWriter
import com.vishnurajeevan.libroabs.download.testBook
import com.vishnurajeevan.libroabs.models.libro.AudioBook
import com.vishnurajeevan.libroabs.models.libro.BookInfo
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class HardcoverSyncTest {
  private val tracker = FakeTracker()
  private val shelves = FakeShelves()
  private val progress = FakeProgress()
  private val writer = FakeWriter()
  private val state = FakeSyncState()
  private val clock = FixedClock(Instant.parse("2026-10-13T12:00:00Z"))

  private fun sync(info: ServerInfo = trackerInfo(), sentToTracker: Map<String, Boolean> = emptyMap()) = HardcoverSync(
    serverInfo = info,
    trackerConnector = tracker,
    libro = shelves,
    progressSource = progress,
    trackerWishlistRepo = FakeWishlist(sentToTracker),
    dbWriter = writer,
    syncState = state,
    logger = silentLogger,
    clock = clock,
    zone = TimeZone.UTC,
  )

  private fun edition(bookId: String, editionId: String, isbn: String, title: String = "Book $isbn") =
    ConnectorBook(bookId, title, connectorAudioBook = listOf(ConnectorAudioBookEdition(editionId, isbn)))

  private fun candidate(id: String, title: String, author: String = "A") =
    TrackerCandidate(id, title, authors = listOf(ConnectorContributor("auth-$id", author)), url = "https://hardcover.app/books/$id")

  private fun listening(current: Int, lastUpdate: Instant, finished: Boolean = false) = ListeningProgress(
    currentSeconds = current, durationSeconds = 3_600, finished = finished,
    startedAt = lastUpdate - 2.days, finishedAt = if (finished) lastUpdate else null, lastUpdate = lastUpdate,
  )

  @Test
  fun marksBooksOwnedThatAreOnHardcoverButNotOwned() = runBlocking {
    shelves.library = listOf(testBook("111"), testBook("222"))
    tracker.editions = listOf(edition("b1", "e1", "111"), edition("b2", "e2", "222"))
    tracker.owned = listOf(edition("b2", "e2", "222"))
    val sync = sync()

    val preview = sync.preview()
    assertEquals(listOf("111"), preview.books.map { it.isbn })
    assertEquals(1, preview.upToDate)

    sync.syncAutomatically()
    assertEquals(listOf("markOwned b1/e1"), tracker.calls)
  }

  @Test
  fun addsAMissingAudiobookEditionToTheBookItMatches() = runBlocking {
    val book = testBook("333", "Artificial Condition: The Murderbot Diaries").copy(
      authors = listOf("Martha Wells"),
      audiobook_info = BookInfo(listOf("Kevin R. Free"), 11_865, 1),
      publisher = "Recorded Books",
    )
    shelves.library = listOf(book)
    tracker.search = { listOf(candidate("hc-1", "Artificial Condition", "Martha Wells")) }
    val sync = sync()

    val plan = sync.preview().books.single()
    assertTrue(plan.match!!.createsEdition)
    assertEquals("https://hardcover.app/books/hc-1", plan.match!!.url)

    sync.syncAutomatically()
    val (created, extras) = tracker.created!!
    assertEquals("hc-1", created.id)
    assertEquals("333", created.connectorAudioBook.single().isbn13)
    assertEquals(11_865, extras.audioSeconds)
    assertEquals(listOf("Kevin R. Free"), extras.narrators)
    assertEquals("Recorded Books", extras.publisher)
    assertTrue("markOwned hc-1/new-edition" in tracker.calls, tracker.calls.toString())
  }

  @Test
  fun aBookWithoutACloseEnoughMatchIsReportedNotGuessed() = runBlocking {
    shelves.library = listOf(testBook("444", "Dune"))
    tracker.search = { listOf(candidate("x", "Dune", author = "Somebody Else")) }
    val preview = sync().preview()
    assertEquals(listOf("Dune"), preview.notFound)
    assertTrue(preview.books.isEmpty())
  }

  @Test
  fun skippedIsbnsAreLeftAlone() = runBlocking {
    shelves.library = listOf(testBook("111"))
    tracker.editions = listOf(edition("b1", "e1", "111"))
    sync(trackerInfo(skip = listOf("111"))).syncAutomatically()
    assertTrue(tracker.calls.isEmpty())
  }

  @Test
  fun wishlistBooksGoToWantToReadOnceWithTheirEdition() = runBlocking {
    shelves.wishlist = listOf(AudioBook("Wanted", "555", listOf("A")), AudioBook("Sent before", "666", listOf("A")))
    tracker.editions = listOf(edition("b5", "e5", "555"), edition("b6", "e6", "666"))
    sync(trackerInfo(mode = TrackerSyncMode.LIBRO_WISHLISTS_TO_HARDCOVER), sentToTracker = mapOf("666" to true)).syncAutomatically()
    assertEquals(listOf("markWanted b5/e5"), tracker.calls)
    assertEquals(listOf("555"), writer.writes.filterIsInstance<TrackerWishlistSyncStatus>().map { it.isbn })
  }

  @Test
  fun wantToReadIsNotWishedForOnceBought() = runBlocking {
    shelves.library = listOf(testBook("777"))
    tracker.wanted = listOf(edition("b7", "e7", "777"), edition("b8", "e8", "888"))
    shelves.details = { testBook(it) }
    sync(trackerInfo(mode = TrackerSyncMode.HARDCOVER_WANT_TO_READ_TO_LIBRO)).syncAutomatically()
    assertEquals(listOf("888"), shelves.addedToWishlist)
  }

  @Test
  fun aWantToReadBookIsWishedForOnceInAnEditionLibroSells() = runBlocking {
    val editions = listOf("901", "902", "903").mapIndexed { i, isbn -> ConnectorAudioBookEdition("e$i", isbn) }
    tracker.wanted = listOf(
      ConnectorBook("b9", "Rogue Protocol", connectorAudioBook = editions),
      ConnectorBook("b10", "Not Sold", connectorAudioBook = listOf(ConnectorAudioBookEdition("e10", "910"))),
    )
    shelves.details = { isbn -> if (isbn == "902") testBook(isbn, "Rogue Protocol") else error("not found") }
    val sync = sync(trackerInfo(mode = TrackerSyncMode.HARDCOVER_WANT_TO_READ_TO_LIBRO))

    val preview = sync.preview()
    assertEquals(listOf("libro:902"), preview.books.flatMap { book -> book.actions.map { it.key } })
    assertEquals(listOf("Not Sold"), preview.notOnLibro)

    sync.syncAutomatically()
    assertEquals(listOf("902"), shelves.addedToWishlist)
  }

  @Test
  fun aWantToReadBookBoughtInAnotherEditionIsNotWishedFor() = runBlocking {
    shelves.library = listOf(testBook("902"))
    tracker.wanted = listOf(
      ConnectorBook("b9", "Rogue Protocol", connectorAudioBook = listOf(ConnectorAudioBookEdition("e1", "901"), ConnectorAudioBookEdition("e2", "902"))),
    )
    shelves.details = { testBook(it) }
    sync(trackerInfo(mode = TrackerSyncMode.HARDCOVER_WANT_TO_READ_TO_LIBRO)).syncAutomatically()
    assertTrue(shelves.addedToWishlist.isEmpty())
  }

  @Test
  fun aDryRunChangesNothing() = runBlocking {
    shelves.library = listOf(testBook("111"), testBook("333", "Artificial Condition"))
    tracker.editions = listOf(edition("b1", "e1", "111"))
    tracker.wanted = listOf(edition("b8", "e8", "888"))
    tracker.search = { listOf(candidate("hc-1", "Artificial Condition")) }
    sync(trackerInfo(mode = TrackerSyncMode.ALL, dryRun = true)).syncAutomatically()
    assertTrue(tracker.calls.all { it.startsWith("search") }, tracker.calls.toString())
    assertNull(tracker.created)
    assertTrue(shelves.addedToWishlist.isEmpty())
  }

  @Test
  fun oneFailingBookDoesNotStopTheRestButIsReported() = runBlocking {
    shelves.library = listOf(testBook("111"), testBook("222"))
    tracker.editions = listOf(edition("b1", "e1", "111"), edition("b2", "e2", "222"))
    tracker.failMarkOwned = { if (it.id == "b1") TrackerException("nope") else null }
    val error = assertFailsWith<IllegalStateException> { sync().syncAutomatically() }
    assertTrue("nope" in error.message!!, error.message)
    assertEquals(listOf("markOwned b2/e2"), tracker.calls)
  }

  @Test
  fun anUnavailableTrackerStopsTheSync() = runBlocking {
    shelves.library = listOf(testBook("111"), testBook("222"))
    tracker.editions = listOf(edition("b1", "e1", "111"), edition("b2", "e2", "222"))
    tracker.failMarkOwned = { TrackerUnavailableException("rate limited") }
    assertFailsWith<IllegalStateException> { sync().syncAutomatically() }
    assertTrue(tracker.calls.isEmpty())
  }

  @Test
  fun applyingDoesOnlyWhatWasTicked() = runBlocking {
    shelves.library = listOf(testBook("111"), testBook("222"))
    tracker.editions = listOf(edition("b1", "e1", "111"), edition("b2", "e2", "222"))
    val sync = sync()
    val preview = sync.preview()

    val result = sync.apply(preview.planId, setOf("owned:222"))
    assertEquals(1, result.applied)
    assertEquals(listOf("markOwned b2/e2"), tracker.calls)
  }

  @Test
  fun anOldPreviewCantBeApplied() = runBlocking {
    val sync = sync()
    val preview = sync.preview()
    clock.now += 2.hours
    assertTrue(sync.apply(preview.planId, setOf("owned:111")).expired)
    assertTrue(sync.apply("made-up", setOf("owned:111")).expired)
  }

  @Test
  fun progressIsPreviewedButOnlySentAutomaticallyWhenTurnedOn() = runBlocking {
    shelves.library = listOf(testBook("111"))
    tracker.editions = listOf(edition("b1", "e1", "111"))
    tracker.owned = tracker.editions
    progress.progress = mapOf("111" to listening(1_800, lastUpdate = clock.now - 1.hours))

    val preview = sync().preview()
    assertEquals("progress:111", preview.books.single().actions.single().key)

    sync().syncAutomatically()
    assertTrue(tracker.calls.isEmpty())
  }

  @Test
  fun turningProgressOnDoesNotReplayEarlierListening() = runBlocking {
    shelves.library = listOf(testBook("111"))
    tracker.editions = listOf(edition("b1", "e1", "111"))
    tracker.owned = tracker.editions
    progress.progress = mapOf("111" to listening(1_800, lastUpdate = clock.now - 1.hours))
    val info = trackerInfo(syncProgress = true)

    sync(info).syncAutomatically()
    assertTrue(tracker.calls.isEmpty(), "listening from before progress sync was turned on")

    clock.now += 1.days
    progress.progress = mapOf("111" to listening(2_400, lastUpdate = clock.now - 1.hours))
    sync(info).syncAutomatically()
    assertEquals("setStatus b1 e1 CURRENTLY_READING", tracker.calls.first())
    assertTrue(tracker.calls.last().startsWith("saveRead ub-b1"), tracker.calls.toString())
  }

  @Test
  fun aBookNotPlayedForAMonthIsShownUnticked() = runBlocking {
    shelves.library = listOf(testBook("111"))
    tracker.editions = listOf(edition("b1", "e1", "111"))
    tracker.owned = tracker.editions
    progress.progress = mapOf("111" to listening(1_800, lastUpdate = clock.now - 60.days))
    val action = sync().preview().books.single().actions.single()
    assertEquals(false, action.selected)
    assertTrue(action.note!!.contains("abandoned"))
  }

  @Test
  fun aReadHardcoverStartsItselfIsFilledInRatherThanDuplicated() = runBlocking {
    shelves.library = listOf(testBook("111"))
    tracker.editions = listOf(edition("b1", "e1", "111"))
    tracker.owned = tracker.editions
    tracker.startsReadOnStatus = true
    tracker.userBooks["b1"] = TrackerUserBook("ub-b1", "b1", ReadingStatus.WANT_TO_READ, null, emptyList())
    progress.progress = mapOf("111" to listening(1_800, lastUpdate = clock.now - 1.hours))
    val sync = sync()
    val preview = sync.preview()

    sync.apply(preview.planId, setOf("progress:111"))
    val saved = tracker.calls.last()
    assertTrue(saved.startsWith("saveRead ub-b1 TrackerRead(id=auto"), saved)
    assertTrue("progressSeconds=1800" in saved && "editionId=e1" in saved, saved)
  }
}
