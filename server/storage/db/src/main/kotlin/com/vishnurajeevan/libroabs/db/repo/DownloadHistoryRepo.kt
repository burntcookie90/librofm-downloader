package com.vishnurajeevan.libroabs.db.repo

import com.vishnurajeevan.libroabs.db.DownloadHistoryQueries
import com.vishnurajeevan.libroabs.db.PdfExtraDownloadHistoryQueries
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.server.ItemDownloadHistory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

interface DownloadHistoryRepo {
  suspend fun isDownloaded(isbn: String): Boolean
  suspend fun downloadCount(): Long

  suspend fun pdfExtrasDownloaded(isbn: String): Boolean

  suspend fun downloadHistory(): List<ItemDownloadHistory>

  suspend fun downloadHistory(isbn: String): ItemDownloadHistory
}

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class RealDownloadHistoryRepo(
  private val downloadHistoryQueries: DownloadHistoryQueries,
  private val pdfExtraDownloadHistoryQueries: PdfExtraDownloadHistoryQueries,
  @Io private val ioDispatcher: CoroutineDispatcher,
) : DownloadHistoryRepo {
  override suspend fun isDownloaded(isbn: String): Boolean = withContext(ioDispatcher) {
    downloadHistoryQueries.isDownloaded(isbn).executeAsOne()
  }

  override suspend fun downloadCount(): Long = withContext(ioDispatcher) {
    downloadHistoryQueries.downloadCount().executeAsOne()
  }

  override suspend fun pdfExtrasDownloaded(isbn: String): Boolean = withContext(ioDispatcher) {
    pdfExtraDownloadHistoryQueries.isDownloaded(isbn).executeAsOne()
  }

  override suspend fun downloadHistory(): List<ItemDownloadHistory> {
    return downloadHistoryQueries.selectAll { isbn, path, format ->
      ItemDownloadHistory(
        isbn = isbn,
        format = format,
        path = path
      )
    }
      .executeAsList()
  }

  override suspend fun downloadHistory(isbn: String): ItemDownloadHistory {
    return downloadHistoryQueries.selectIsbn(isbn) { isbn, path, format ->
      ItemDownloadHistory(
        isbn = isbn,
        format = format,
        path = path
      )
    }.executeAsOne()
  }
}