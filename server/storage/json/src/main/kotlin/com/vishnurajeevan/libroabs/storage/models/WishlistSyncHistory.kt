package com.vishnurajeevan.libroabs.storage.models

import com.vishnurajeevan.libroabs.models.libro.WishlistItemSyncStatus
import kotlinx.serialization.Serializable

@Serializable
data class WishlistSyncHistory(
  val history: Map<String, WishlistItemSyncStatus>
)

