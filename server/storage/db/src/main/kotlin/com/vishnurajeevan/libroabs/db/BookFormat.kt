package com.vishnurajeevan.libroabs.db


/**
 * Different from [BookFormat] because we dont want to save the fallback
 */
enum class BookFormat() {
  M4B, M4B_CONVERTED, MP3
}