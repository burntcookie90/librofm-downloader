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
  /**
   * ISBNs that should not be considered for edition creation right now: everything we have already
   * written to the tracker, plus anything we failed to match more recently than
   * [unmatchedRetryCutoffEpochSeconds].
   */
  suspend fun getIsbnsToSkip(unmatchedRetryCutoffEpochSeconds: Long): Set<String>
}

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class RealTrackerCreatedEditionRepo(
  private val queries: TrackerCreatedEditionQueries,
  @Io private val ioDispatcher: CoroutineDispatcher,
) : TrackerCreatedEditionRepo {

  override suspend fun getIsbnsToSkip(
    unmatchedRetryCutoffEpochSeconds: Long
  ): Set<String> = withContext(ioDispatcher) {
    buildSet {
      addAll(queries.getWriteAttemptedIsbns().executeAsList())
      addAll(queries.getRecentlyUnmatchedIsbns(unmatchedRetryCutoffEpochSeconds).executeAsList())
    }
  }
}
