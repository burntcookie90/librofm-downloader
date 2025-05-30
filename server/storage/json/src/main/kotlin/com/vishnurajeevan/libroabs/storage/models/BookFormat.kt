package com.vishnurajeevan.libroabs.storage.models

import kotlinx.serialization.Serializable

/**
 * Different from [BookFormat] because we dont want to save the fallback
 */
@Serializable
enum class Format() {
  M4B, M4B_CONVERTED, MP3
}