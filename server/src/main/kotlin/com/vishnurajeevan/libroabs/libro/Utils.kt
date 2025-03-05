package com.vishnurajeevan.libroabs.libro

import io.ktor.http.*

fun createFilenames(tracks: List<Tracks>, title: String) = tracks
  .map { track ->
    "${track.number.padToTotal(tracks.size)} - $title - ${track.chapter_title}".sanitizeForFilename()
  }

fun createTrackTitles(tracks: List<Tracks>) = tracks
  .map { track ->
    "${track.number.padToTotal(tracks.size)} - ${track.chapter_title}"
  }

private fun Int.padToTotal(total: Int): String {
  return toString().padStart(total.toString().length, '0')
}

private fun String?.sanitizeForFilename(): String {
  if (this == null) return "null"
  return this
    .replace("\"", "\\\"") // Escape quotes
    .replace("""[<>:/\\|?*]""".toRegex(), "") // Remove other illegal characters
    .replace("""[\x00-\x1F]""".toRegex(), "") // Remove control characters
    .replace("""[.]$""".toRegex(), "") // Remove trailing dots
    .trim()
    .take(255) // Ensure filename component isn't too long
}
