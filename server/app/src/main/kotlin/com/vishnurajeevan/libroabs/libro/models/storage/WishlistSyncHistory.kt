package com.vishnurajeevan.libroabs.libro.models.storage

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