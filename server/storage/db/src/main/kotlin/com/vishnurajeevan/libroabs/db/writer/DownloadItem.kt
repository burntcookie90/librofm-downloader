package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.DownloadHistoryQueries
import com.vishnurajeevan.libroabs.db.Download_history
import com.vishnurajeevan.libroabs.models.server.DownloadedFormat

data class DownloadItem(
  val isbn: String,
  val format: DownloadedFormat,
  val path: String,
): DbWrite

fun DownloadItem.handle(downloadHistoryQueries: DownloadHistoryQueries) {
  downloadHistoryQueries.insertDownload(
    Download_history(
      isbn = isbn,
      path = path,
      format = format,
    )
  )
}
