package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.DownloadHistoryQueries

data class DeleteDownloadHistoryItem(
  val isbn: String
): DbWrite

fun DeleteDownloadHistoryItem.handle(downloadHistoryQueries: DownloadHistoryQueries) {
  downloadHistoryQueries.deleteIsbn(isbn)
}
