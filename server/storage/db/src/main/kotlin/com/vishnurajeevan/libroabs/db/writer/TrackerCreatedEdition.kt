package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.TrackerCreatedEditionQueries
import com.vishnurajeevan.libroabs.db.Tracker_created_edition

/**
 * Records that we have already attempted to create a tracker edition for [isbn].
 *
 * This is written whether or not the creation succeeded. A failed attempt is recorded so that we do
 * not re-attempt the same insert on every sync: repeatedly issuing `insert_edition` against a shared
 * community database is the failure mode we most want to avoid.
 */
data class TrackerCreatedEdition(
  val isbn: String,
  val wasCreated: Boolean,
) : DbWrite

fun TrackerCreatedEdition.handle(queries: TrackerCreatedEditionQueries) {
  queries.insertCreatedEdition(
    Tracker_created_edition(
      isbn,
      was_created = wasCreated
    )
  )
}
