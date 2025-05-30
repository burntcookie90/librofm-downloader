package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libroabs.db.repo.DownloadHistoryRepo
import com.vishnurajeevan.libroabs.db.repo.WishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.DownloadItem
import com.vishnurajeevan.libroabs.db.writer.WishlistSyncStatus
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.server.DownloadedFormat
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.storage.Storage
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import com.vishnurajeevan.libroabs.storage.models.LibroDownloadHistory
import com.vishnurajeevan.libroabs.storage.models.WishlistSyncHistory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File

@Inject
@SingleIn(AppScope::class)
class StorageMigrator(
  private val downloadHistoryStorage: Storage<LibroDownloadHistory>,
  private val wishlistHistoryStorage: Storage<WishlistSyncHistory>,
  private val localLibrary: Storage<LibraryMetadata>,
  private val downloadHistoryRepo: DownloadHistoryRepo,
  private val wishlistSyncStatusRepo: WishlistSyncStatusRepo,
  private val dbWriter: DbWriter,
  private val serverInfo: ServerInfo,
  private val lfdLogger: Logger,
  private val targetDir: (Book) -> File,
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
      // We need to prefill the download history with the old filesystem based history
      if (File(serverInfo.mediaDir).listFiles().isNotEmpty()) {
        lfdLogger.log("Migrating to file based history")
        localLibrary.getData().audiobooks
          .forEach { book ->
            val targetDir = targetDir(book)
            if (targetDir.exists() && targetDir.listFiles().isNotEmpty()) {
              val isMp3 = targetDir.listFiles().map { it.extension }.any { it == "mp3" }
              dbWriter.write(
                DownloadItem(
                  isbn = book.isbn,
                  format = if (isMp3) DownloadedFormat.MP3 else DownloadedFormat.M4B,
                  path = targetDir.path
                )
              )
            }
          }
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