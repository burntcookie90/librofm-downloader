package com.vishnurajeevan.libroabs.models.server

enum class TrackerSyncMode {
  LIBRO_WISHLISTS_TO_HARDCOVER, // Sync Libro wishlists to Hardcover
  LIBRO_OWNED_TO_HARDCOVER, // Sync Libro owned books to Hardcover
  LIBRO_ALL_TO_HARDCOVER, // Sync both Libro wishlist and owned books to Hardcover
  HARDCOVER_WANT_TO_READ_TO_LIBRO, // Sync Hardcover want-to-read books to Libro
  ALL,
}