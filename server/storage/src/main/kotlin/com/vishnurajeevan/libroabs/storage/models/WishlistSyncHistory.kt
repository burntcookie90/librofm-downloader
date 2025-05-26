package com.vishnurajeevan.libroabs.storage.models

import kotlinx.serialization.Serializable

@Serializable
data class WishlistSyncHistory(
  val history: Map<String, WishlistItemSyncStatus>
)

@Serializable
enum class WishlistItemSyncStatus {
  SUCCESS,
  FAILURE,
}