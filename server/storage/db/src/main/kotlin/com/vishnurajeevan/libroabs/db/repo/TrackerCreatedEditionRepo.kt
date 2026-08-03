package com.vishnurajeevan.libroabs.db.repo

import com.vishnurajeevan.libroabs.db.TrackerCreatedEditionQueries
import com.vishnurajeevan.libroabs.models.graph.Io
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface TrackerCreatedEditionRepo {
  /** ISBNs we have already attempted to create an edition for, successful or not. */
  suspend fun getAttemptedIsbns(): List<String>
}

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class RealTrackerCreatedEditionRepo(
  private val queries: TrackerCreatedEditionQueries,
  @Io private val ioDispatcher: CoroutineDispatcher,
) : TrackerCreatedEditionRepo {

  override suspend fun getAttemptedIsbns(): List<String> = withContext(ioDispatcher) {
    queries.getIsbns().executeAsList()
  }
}
