package com.vishnurajeevan.libroabs.options

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.vishnurajeevan.libroabs.models.server.AbsImportMode
import com.vishnurajeevan.libroabs.models.server.AudiobookshelfConfig

class AudiobookshelfOptionGroup : OptionGroup() {
  val url by option("--abs-url", envvar = "ABS_URL")
    .required()

  val apiKey by option("--abs-api-key", envvar = "ABS_API_KEY", help = "API key of an Audiobookshelf admin user")
    .required()

  val libraryId by option("--abs-library-id", envvar = "ABS_LIBRARY_ID")

  val mediaPath by option(
    "--abs-media-path",
    envvar = "ABS_MEDIA_PATH",
    help = "Where Audiobookshelf sees the media folder, when that isn't the library's only folder",
  )

  val mode by option("--abs-import-mode", envvar = "ABS_IMPORT_MODE")
    .enum<AbsImportMode>(ignoreCase = true)
    .default(AbsImportMode.SHARED)

  fun toConfig() = AudiobookshelfConfig(
    url = url,
    apiKey = apiKey,
    libraryId = libraryId?.takeIf { it.isNotBlank() },
    mediaPath = mediaPath?.takeIf { it.isNotBlank() },
    mode = mode,
  )
}
