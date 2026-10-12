package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.DownloadPart
import com.vishnurajeevan.libroabs.models.libro.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.models.libro.PdfExtra
import com.vishnurajeevan.libroabs.models.server.M4bMetadata
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import java.io.File

/** The part of the libro.fm API used to list the library and fetch book files. */
interface LibroFmBooks {
  suspend fun getLocalLibrary(): LibraryMetadata
  suspend fun fetchBookDetails(isbn: String): Book
  suspend fun fetchMp3DownloadMetadata(isbn: String): Mp3DownloadMetadata
  suspend fun fetchM4bMetadata(isbn: String): Result<M4bMetadata>
  suspend fun downloadM4b(m4bUrl: String, targetDirectory: File)
  /** Downloads and extracts the MP3 parts, returning the extracted files. */
  suspend fun downloadMp3s(data: List<DownloadPart>, targetDirectory: File): List<File>
  suspend fun downloadPdfExtras(isbn: String, data: List<PdfExtra>, targetDirectory: File)
}
