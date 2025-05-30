package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libroabs.db.repo.DownloadHistoryRepo
import com.vishnurajeevan.libroabs.db.repo.WishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.DownloadItem
import com.vishnurajeevan.libroabs.db.writer.WishlistSyncStatus
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.storage.Storage
import com.vishnurajeevan.libroabs.storage.models.LibroDownloadHistory
import com.vishnurajeevan.libroabs.storage.models.WishlistSyncHistory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@Inject
@SingleIn(AppScope::class)
class StorageMigrator(
  private val downloadHistoryStorage: Storage<LibroDownloadHistory>,
  private val wishlistHistoryStorage: Storage<WishlistSyncHistory>,
  private val downloadHistoryRepo: DownloadHistoryRepo,
  private val wishlistSyncStatusRepo: WishlistSyncStatusRepo,
  private val dbWriter: DbWriter,
  @Io private val ioDispatcher: CoroutineDispatcher,
) {
  suspend fun migrate() = withContext(ioDispatcher) {
    if (downloadHistoryRepo.downloadCount() == 0L) {
      downloadHistoryStorage.getData()
        .books
        .forEach {
          dbWriter.write(
            DownloadItem(
              isbn = it.value.isbn,
              format = it.value.format,
              path = it.value.path
            )
          )
        }
    }

    if (wishlistSyncStatusRepo.getSyncedIsbns().isEmpty()) {
      wishlistHistoryStorage.getData()
        .history
        .forEach {
          dbWriter.write(
            WishlistSyncStatus(
              isbn = it.key,
              status = it.value
            )
          )
        }
    }
  }
}