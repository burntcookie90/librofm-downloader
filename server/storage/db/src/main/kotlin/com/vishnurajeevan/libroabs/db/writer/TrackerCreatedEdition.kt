package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.TrackerCreatedEditionQueries
import com.vishnurajeevan.libroabs.db.Tracker_created_edition

/**
 * Records that we considered creating a tracker edition for [isbn].
 *
 * [writeAttempted] is the field that matters. It distinguishes the two outcomes, which need opposite
 * treatment:
 *
 * - `true` — we called `insert_edition`. Whether it succeeded, failed, or errored halfway, a write
 *   may have landed on the tracker, so this ISBN must never be retried. Retrying is what produces
 *   duplicate editions in a shared database.
 * - `false` — search found no match, so nothing was written. There is no duplicate to create, and
 *   permanently writing the book off would be wrong: the tracker's catalog grows over time, and books
 *   that cannot be matched today may match later. These are retried after a cooldown.
 */
data class TrackerCreatedEdition(
  val isbn: String,
  val writeAttempted: Boolean,
  val attemptedAtEpochSeconds: Long,
) : DbWrite

fun TrackerCreatedEdition.handle(queries: TrackerCreatedEditionQueries) {
  queries.insertCreatedEdition(
    Tracker_created_edition(
      isbn,
      write_attempted = writeAttempted,
      attempted_at_epoch_seconds = attemptedAtEpochSeconds
    )
  )
}
