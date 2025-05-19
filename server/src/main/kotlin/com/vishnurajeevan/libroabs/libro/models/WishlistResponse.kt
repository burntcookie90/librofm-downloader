package com.vishnurajeevan.libroabs.libro.models

import kotlinx.serialization.Serializable

@Serializable
data class WishlistResponse(
  val data: WishlistResponseData
)


@Serializable
data class WishlistResponseData(
  val wishlist: WishlistWishlist
)

@Serializable
data class WishlistWishlist(
  val audiobooks: List<AudioBook>
)

@Serializable
data class AudioBook(
  val title: String
  ,
  val isbn: String,
  val authors: List<String>
)
