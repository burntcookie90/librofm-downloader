package com.vishnurajeevan.libroabs.models.server

import kotlinx.serialization.Serializable

/**
 * Different from [BookFormat] because we dont want to save the fallback
 */
@Serializable
enum class DownloadedFormat() {
  M4B, M4B_CONVERTED, MP3
}