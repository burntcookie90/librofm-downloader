package com.vishnurajeevan.libroabs.db.repo

import com.vishnurajeevan.libroabs.db.SyncStateQueries
import com.vishnurajeevan.libroabs.models.graph.Io
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Small values a sync needs to remember between runs, by key. */
interface SyncStateRepo {
  suspend fun get(key: String): String?

  suspend fun put(key: String, value: String)
}

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class RealSyncStateRepo(
  private val queries: SyncStateQueries,
  @Io private val ioDispatcher: CoroutineDispatcher,
) : SyncStateRepo {
  override suspend fun get(key: String): String? = withContext(ioDispatcher) {
    queries.get(key).executeAsOneOrNull()
  }

  override suspend fun put(key: String, value: String): Unit = withContext(ioDispatcher) {
    queries.put(key, value)
  }
}
