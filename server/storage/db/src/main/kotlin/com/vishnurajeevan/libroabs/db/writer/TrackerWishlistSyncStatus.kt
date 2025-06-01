package com.vishnurajeevan.libroabs.db.writer

import com.vishnurajeevan.libroabs.db.TrackerWishlistSyncStatusQueries
import com.vishnurajeevan.libroabs.db.Tracker_wishlist_sync_status
import com.vishnurajeevan.libroabs.models.libro.WishlistItemSyncStatus

data class TrackerWishlistSyncStatus(
  val isbn: String,
  val status: WishlistItemSyncStatus
) : DbWrite

fun TrackerWishlistSyncStatus.handle(queries: TrackerWishlistSyncStatusQueries) {
  queries.insertTrackerSync(
    Tracker_wishlist_sync_status(
      isbn,
      is_sync_successful = (status == WishlistItemSyncStatus.SUCCESS)
    )
  )
}
