package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.DownloadHistoryQueries
import com.vishnurajeevan.libroabs.db.PdfExtraDownloadHistoryQueries
import com.vishnurajeevan.libroabs.db.TrackerWishlistSyncStatusQueries
import com.vishnurajeevan.libroabs.db.WishlistSyncStatusQueries
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class RealDbWriter(
  private val downloadHistoryQueries: DownloadHistoryQueries,
  private val wishlistSyncStatusQueries: WishlistSyncStatusQueries,
  private val trackerWishlistSyncStatusQueries: TrackerWishlistSyncStatusQueries,
  private val pdfExtraDownloadHistoryQueries: PdfExtraDownloadHistoryQueries,
  @Io private val ioDispatcher: CoroutineDispatcher,
  private val logger: Logger,
) : DbWriter {
  override suspend fun write(write: DbWrite): Unit = withContext(ioDispatcher) {
    logger.v("Writing $write to db")
    when (write) {
      is LibroFmWishlistSyncStatus -> write.handle(wishlistSyncStatusQueries)
      is DownloadItem -> write.handle(downloadHistoryQueries)
      is TrackerWishlistSyncStatus -> write.handle(trackerWishlistSyncStatusQueries)
      is DownloadPdfExtraItem -> write.handle(pdfExtraDownloadHistoryQueries)
    }
  }
}