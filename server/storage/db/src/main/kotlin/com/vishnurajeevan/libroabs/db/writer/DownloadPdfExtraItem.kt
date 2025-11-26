package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.PdfExtraDownloadHistoryQueries
import com.vishnurajeevan.libroabs.db.Pdf_extra_download_history

data class DownloadPdfExtraItem(
  val isbn: String
): DbWrite

fun DownloadPdfExtraItem.handle(pdfExtraDownloadHistoryQueries: PdfExtraDownloadHistoryQueries) {
  pdfExtraDownloadHistoryQueries.insertDownload(
    Pdf_extra_download_history(isbn)
  )
}
