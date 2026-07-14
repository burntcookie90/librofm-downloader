package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.DownloadHistoryQueries
import com.vishnurajeevan.libroabs.db.PdfExtraDownloadHistoryQueries

data class DeleteDownloadHistoryItem(
  val isbn: String
): DbWrite

fun DeleteDownloadHistoryItem.handle(
  downloadHistoryQueries: DownloadHistoryQueries,
  pdfExtraDownloadHistoryQueries: PdfExtraDownloadHistoryQueries
) {
  downloadHistoryQueries.deleteIsbn(isbn)
  pdfExtraDownloadHistoryQueries.deleteIsbn(isbn)
}
