package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.WishlistSyncStatusQueries
import com.vishnurajeevan.libroabs.db.Wishlist_sync_status
import com.vishnurajeevan.libroabs.models.libro.WishlistItemSyncStatus

data class SyncStatus(
  val isbn: String,
  val status: WishlistItemSyncStatus
): DbWrite

fun SyncStatus.handle(wishlistSyncStatusQueries: WishlistSyncStatusQueries) {
  wishlistSyncStatusQueries.insertSync(
    wishlist_sync_status = Wishlist_sync_status(
      isbn,
      is_sync_successful = (status == WishlistItemSyncStatus.SUCCESS)
    )
  )
}
