package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.WishlistSyncStatusQueries
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import kotlin.math.log

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class RealDbWriter(
  private val wishlistSyncStatusQueries: WishlistSyncStatusQueries,
  @Io private val ioDispatcher: CoroutineDispatcher,
  private val logger: Logger,
) : DbWriter {
  override suspend fun write(write: DbWrite): Unit = withContext(ioDispatcher) {
    logger.log("Writing $write to db")
    when (write) {
      is SyncStatus -> write.handle(wishlistSyncStatusQueries)
    }
  }
}