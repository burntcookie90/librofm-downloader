package com.vishnurajeevan.libroabs.models.libro

import kotlinx.serialization.Serializable

@Serializable
enum class WishlistItemSyncStatus {
  SUCCESS,
  FAILURE,
}