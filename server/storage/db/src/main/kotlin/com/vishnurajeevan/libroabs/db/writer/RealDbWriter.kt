package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.DownloadHistoryQueries
import com.vishnurajeevan.libroabs.db.TrackerWishlistSyncStatusQueries
import com.vishnurajeevan.libroabs.db.WishlistSyncStatusQueries
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class RealDbWriter(
  private val downloadHistoryQueries: DownloadHistoryQueries,
  private val wishlistSyncStatusQueries: WishlistSyncStatusQueries,
  private val trackerWishlistSyncStatusQueries: TrackerWishlistSyncStatusQueries,
  @Io private val ioDispatcher: CoroutineDispatcher,
  private val logger: Logger,
) : DbWriter {
  override suspend fun write(write: DbWrite): Unit = withContext(ioDispatcher) {
    logger.log("Writing $write to db")
    when (write) {
      is LibroFmWishlistSyncStatus -> write.handle(wishlistSyncStatusQueries)
      is DownloadItem -> write.handle(downloadHistoryQueries)
      is TrackerWishlistSyncStatus -> write.handle((trackerWishlistSyncStatusQueries))
    }
  }
}