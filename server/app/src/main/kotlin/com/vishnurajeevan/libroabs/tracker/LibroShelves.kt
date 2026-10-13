package com.vishnurajeevan.libroabs.tracker

import com.vishnurajeevan.libroabs.db.repo.DownloadHistoryRepo
import com.vishnurajeevan.libroabs.db.repo.LibroFmWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.libro.LibroApiHandler
import com.vishnurajeevan.libroabs.models.libro.AudioBook
import com.vishnurajeevan.libroabs.models.libro.Book
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import java.io.File

/** The libro.fm side of the tracker sync. */
interface LibroShelves {
  suspend fun library(): List<Book>
  suspend fun wishlist(): List<AudioBook>
  suspend fun bookDetails(isbn: String): Book

  /** ISBNs once added to the wishlist from the tracker, which aren't added again if they are later removed. */
  suspend fun addedFromTracker(): Set<String>
  suspend fun addToWishlist(isbns: List<String>)

  /** Where each downloaded book was saved, by ISBN. */
  suspend fun downloadDirs(): Map<String, File>
}

@Inject
@ContributesBinding(AppScope::class)
class RealLibroShelves(
  private val libroClient: LibroApiHandler,
  private val libroWishlistRepo: LibroFmWishlistSyncStatusRepo,
  private val downloadHistoryRepo: DownloadHistoryRepo,
) : LibroShelves {
  override suspend fun library(): List<Book> = libroClient.getLocalLibrary().audiobooks
  override suspend fun wishlist(): List<AudioBook> = libroClient.fetchWishlist().audiobooks
  override suspend fun bookDetails(isbn: String): Book = libroClient.fetchBookDetails(isbn)
  override suspend fun addedFromTracker(): Set<String> = libroWishlistRepo.getSyncedIsbns().toSet()
  override suspend fun addToWishlist(isbns: List<String>) = libroClient.syncWishlist(isbns)
  override suspend fun downloadDirs(): Map<String, File> =
    downloadHistoryRepo.downloadHistory().associate { it.isbn to File(it.path) }
}
