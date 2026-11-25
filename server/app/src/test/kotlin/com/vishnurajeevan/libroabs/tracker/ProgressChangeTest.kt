package com.vishnurajeevan.libroabs.tracker

import com.vishnurajeevan.libroabs.audiobookshelf.ListeningProgress
import com.vishnurajeevan.libroabs.connector.ReadingStatus
import com.vishnurajeevan.libroabs.connector.TrackerRead
import com.vishnurajeevan.libroabs.connector.TrackerUserBook
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class ProgressChangeTest {
  private val zone = TimeZone.UTC
  private val now = Instant.parse("2026-10-13T12:00:00Z")
  private val started = Instant.parse("2026-09-01T08:00:00Z")

  private fun listening(current: Int, finished: Boolean = false, lastUpdate: Instant = now - 1.days) = ListeningProgress(
    currentSeconds = current,
    durationSeconds = 36_000,
    finished = finished,
    startedAt = started,
    finishedAt = if (finished) Instant.parse("2026-10-10T20:00:00Z") else null,
    lastUpdate = lastUpdate,
  )

  private fun userBook(status: ReadingStatus?, vararg reads: TrackerRead) = TrackerUserBook("ub", "book", status, "ed", reads.toList())
  private fun read(id: String, startedAt: String?, finishedAt: String?, progress: Int?) =
    TrackerRead(id, startedAt?.let(LocalDate::parse), finishedAt?.let(LocalDate::parse), progress, "ed")

  @Test
  fun aNewListenStartsReading() {
    val change = progressChange(listening(9_000), null, now, zone)!!
    assertEquals(ReadingStatus.CURRENTLY_READING, change.status)
    assertEquals(TrackerRead(null, LocalDate(2026, 9, 1), null, 9_000, null), change.read)
    assertEquals("Currently reading, 2h 30m of 10h 00m (25%)", change.description)
  }

  @Test
  fun aFinishedListenMarksItReadOnTheDayItFinished() {
    val change = progressChange(listening(36_000, finished = true), userBook(ReadingStatus.CURRENTLY_READING, read("r1", "2026-09-01", null, 9_000)), now, zone)!!
    assertEquals(ReadingStatus.READ, change.status)
    assertEquals(read("r1", "2026-09-01", "2026-10-10", 36_000).copy(editionId = null), change.read)
    assertEquals("Mark as read, finished 10 Oct 2026", change.description)
  }

  @Test
  fun aFinishAlreadyRecordedIsLeftAlone() {
    assertNull(progressChange(listening(36_000, finished = true), userBook(ReadingStatus.READ, read("r1", "2026-09-01", "2026-10-11", null)), now, zone))
  }

  @Test
  fun hardcoverIsNeverMovedBackwards() {
    assertNull(progressChange(listening(9_000), userBook(ReadingStatus.CURRENTLY_READING, read("r1", "2026-09-01", null, 20_000)), now, zone))
    // Finished on Hardcover after this listen began: Hardcover knows better.
    assertNull(progressChange(listening(9_000), userBook(ReadingStatus.READ, read("r1", "2026-09-01", "2026-09-20", null)), now, zone))
  }

  @Test
  fun smallMovesArentWorthARequest() {
    assertNull(progressChange(listening(9_030), userBook(ReadingStatus.CURRENTLY_READING, read("r1", "2026-09-01", null, 9_000)), now, zone))
  }

  @Test
  fun movesTheOpenReadForward() {
    val change = progressChange(listening(18_000), userBook(ReadingStatus.CURRENTLY_READING, read("r1", "2026-08-30", null, 9_000)), now, zone)!!
    assertNull(change.status)
    assertEquals(read("r1", "2026-08-30", null, 18_000).copy(editionId = null), change.read)
    assertEquals("Update progress from 2h 30m to 5h 00m of 10h 00m (50%)", change.description)
  }

  @Test
  fun aStatusTheUserChoseStands() {
    for (status in listOf(ReadingStatus.PAUSED, ReadingStatus.DID_NOT_FINISH, ReadingStatus.IGNORED)) {
      assertNull(progressChange(listening(9_000), userBook(status), now, zone), "$status")
    }
  }

  @Test
  fun listeningAgainAfterAnEarlierReadStartsANewRead() {
    val change = progressChange(listening(9_000), userBook(ReadingStatus.READ, read("r1", "2024-01-01", "2024-02-01", null)), now, zone)!!
    assertEquals(ReadingStatus.CURRENTLY_READING, change.status)
    assertNull(change.read?.id)
  }

  @Test
  fun aBookNotPlayedForAMonthIsStale() {
    assertTrue(progressChange(listening(9_000, lastUpdate = now - 45.days), null, now, zone)!!.stale)
    assertEquals(false, progressChange(listening(9_000, lastUpdate = now - 3.days), null, now, zone)!!.stale)
  }

  @Test
  fun aFewMinutesInIsNotReading() {
    assertNull(progressChange(listening(60), null, now, zone))
  }
}
