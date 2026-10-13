package com.vishnurajeevan.libroabs.tracker

import com.vishnurajeevan.libroabs.audiobookshelf.ListeningProgress
import com.vishnurajeevan.libroabs.connector.ReadingStatus
import com.vishnurajeevan.libroabs.connector.TrackerRead
import com.vishnurajeevan.libroabs.connector.TrackerUserBook
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** What to tell the tracker so it shows the listening progress. */
internal data class ProgressChange(
  /** The status to set, or `null` to leave it. */
  val status: ReadingStatus?,
  /** The read to add (no id) or update, or `null` to leave the reads alone. */
  val read: TrackerRead?,
  val description: String,
  /** Not listened to for a while, so perhaps abandoned; not sent unless asked. */
  val stale: Boolean,
)

/** Statuses the user chose on the tracker that listening progress shouldn't override. */
private val deliberate = setOf(ReadingStatus.PAUSED, ReadingStatus.DID_NOT_FINISH, ReadingStatus.IGNORED)

/** Less listening than this doesn't count as having started the book. */
private const val MIN_LISTENED_SECONDS = 5 * 60

/** Progress moves of less than this aren't worth a request. */
private const val MIN_PROGRESS_STEP_SECONDS = 60

/**
 * Works out how to bring [userBook] (`null` when the user doesn't have the book on the tracker) in line with
 * [progress], or `null` when it already is. The tracker is never moved backwards: a finish recorded there since this
 * listen began, a read further along, or a status the user chose (paused, did not finish, ignored) all stand.
 */
internal fun progressChange(
  progress: ListeningProgress,
  userBook: TrackerUserBook?,
  now: Instant,
  zone: TimeZone,
): ProgressChange? {
  val started = progress.startedAt?.toLocalDateTime(zone)?.date
  val reads = userBook?.reads.orEmpty()
  val open = reads.firstOrNull { it.finishedAt == null }
  val finishedSinceStarted = reads.any { read -> read.finishedAt?.let { started == null || it >= started } == true }
  if (finishedSinceStarted) return null
  val status = userBook?.status

  if (progress.finished) {
    val finished = (progress.finishedAt ?: progress.lastUpdate ?: now).toLocalDateTime(zone).date
    return ProgressChange(
      status = ReadingStatus.READ.takeIf { status != ReadingStatus.READ },
      read = TrackerRead(
        id = open?.id,
        startedAt = open?.startedAt ?: started,
        finishedAt = finished,
        progressSeconds = progress.durationSeconds.takeIf { it > 0 },
        editionId = null,
      ),
      description = "Mark as read, finished ${finished.display()}",
      stale = false,
    )
  }

  if (status in deliberate) return null
  // A few minutes in is a sample or a stray tap, not a read.
  if (progress.currentSeconds < MIN_LISTENED_SECONDS) return null
  // Read before, now listening again: that's a new read.
  val newStatus = ReadingStatus.CURRENTLY_READING.takeIf { status == null || status == ReadingStatus.WANT_TO_READ || status == ReadingStatus.READ }
  val behind = open == null || (open.progressSeconds ?: 0) < progress.currentSeconds - MIN_PROGRESS_STEP_SECONDS
  if (newStatus == null && !behind) return null
  val position = "${progress.currentSeconds.hoursMinutes()} of ${progress.durationSeconds.hoursMinutes()}" +
    if (progress.durationSeconds > 0) " (${progress.currentSeconds * 100 / progress.durationSeconds}%)" else ""
  val recorded = open?.progressSeconds
  val description = if (recorded != null && newStatus == null) {
    "Update progress from ${recorded.hoursMinutes()} to $position"
  } else {
    "Currently reading, $position"
  }
  return ProgressChange(
    status = newStatus,
    read = if (behind) {
      TrackerRead(
        id = open?.id,
        startedAt = open?.startedAt ?: started,
        finishedAt = null,
        progressSeconds = progress.currentSeconds,
        editionId = null,
      )
    } else {
      null
    },
    description = description,
    stale = progress.lastUpdate?.let { now - it > STALE_AFTER } ?: true,
  )
}

/** In-progress books not played for this long might have been abandoned. */
internal val STALE_AFTER = 30.days

private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

internal fun LocalDate.display(): String = "$day ${months[month.ordinal]} $year"

internal fun Int.hoursMinutes(): String {
  val hours = this / 3600
  val minutes = this % 3600 / 60
  return if (hours > 0) "${hours}h ${minutes.toString().padStart(2, '0')}m" else "${minutes}m"
}
