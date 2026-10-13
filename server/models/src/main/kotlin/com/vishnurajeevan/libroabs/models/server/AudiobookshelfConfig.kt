package com.vishnurajeevan.libroabs.models.server

import dev.zacsweers.redacted.annotations.Redacted

enum class AbsImportMode {
  /** Audiobookshelf reads the same media folder; it is told which folder to add. */
  SHARED,

  /** Audiobookshelf can't see the media folder; the book's files are uploaded to it. */
  UPLOAD,
}

data class AudiobookshelfConfig(
  val url: String,
  @Redacted val apiKey: String,
  /** Needed only when Audiobookshelf has more than one book library. */
  val libraryId: String? = null,
  /** Where Audiobookshelf sees the media folder. Defaults to the library's only folder. */
  val mediaPath: String? = null,
  val mode: AbsImportMode = AbsImportMode.SHARED,
)
