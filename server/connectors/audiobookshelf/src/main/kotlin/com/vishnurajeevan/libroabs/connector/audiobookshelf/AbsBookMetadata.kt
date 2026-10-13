package com.vishnurajeevan.libroabs.connector.audiobookshelf

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The book details that can be updated in Audiobookshelf one by one. */
enum class AbsField(val label: String) {
  TITLE("Title"),
  SUBTITLE("Subtitle"),
  AUTHORS("Authors"),
  NARRATORS("Narrators"),
  SERIES("Series"),
  GENRES("Genres"),
  PUBLISHER("Publisher"),
  PUBLISHED("Published"),
  DESCRIPTION("Description"),
  ISBN("ISBN"),
  LANGUAGE("Language"),
}

/** Everything we know about a book, in the shape Audiobookshelf stores it. */
data class AbsBookMetadata(
  val title: String,
  val subtitle: String? = null,
  val authors: List<String>,
  val narrators: List<String> = emptyList(),
  val series: String? = null,
  val seriesSequence: String? = null,
  val genres: List<String> = emptyList(),
  val tags: List<String> = emptyList(),
  val publisher: String? = null,
  /** `yyyy-MM-dd`. */
  val publishedDate: String? = null,
  val description: String? = null,
  val isbn: String? = null,
  val language: String? = null,
  /** The year, when there is no full [publishedDate]. */
  val publishedYearOverride: String? = null,
) {
  val publishedYear: String? get() = publishedDate?.take(4) ?: publishedYearOverride

  /**
   * The `metadata.json` Audiobookshelf reads from a book's folder when it scans. By default it outranks every other
   * source, so the book keeps this metadata however often the library is rescanned. With [chapters] the chapter
   * names survive rescans too.
   */
  fun toMetadataFile(chapters: List<AbsChapter> = emptyList()): String = metadataJson.encodeToString(
    MetadataFile.serializer(),
    MetadataFile(
      title = title,
      subtitle = subtitle,
      authors = authors,
      narrators = narrators,
      // Audiobookshelf splits "Name #2" into the series and its sequence.
      series = listOfNotNull(series?.let { name -> seriesSequence?.let { "$name #$it" } ?: name }),
      genres = genres,
      tags = tags,
      publishedYear = publishedYear,
      publishedDate = publishedDate,
      publisher = publisher,
      description = description,
      isbn = isbn,
      language = language,
      chapters = chapters.ifEmpty { null },
    ),
  )

  /**
   * The body for `PATCH /api/items/:id/media`. Only [fields] that have a value are sent; anything else is left as it
   * is in Audiobookshelf.
   */
  internal fun toMediaUpdate(fields: Set<AbsField> = AbsField.entries.toSet()): MediaUpdate {
    fun <T> pick(field: AbsField, value: T): T? = value.takeIf { field in fields }
    return MediaUpdate(
      metadata = MetadataUpdate(
        title = pick(AbsField.TITLE, title),
        subtitle = pick(AbsField.SUBTITLE, subtitle),
        authors = pick(AbsField.AUTHORS, authors.map(::Name).ifEmpty { null }),
        narrators = pick(AbsField.NARRATORS, narrators.ifEmpty { null }),
        series = pick(AbsField.SERIES, series?.let { listOf(Series(it, seriesSequence)) }),
        genres = pick(AbsField.GENRES, genres.ifEmpty { null }),
        publishedYear = pick(AbsField.PUBLISHED, publishedYear),
        publishedDate = pick(AbsField.PUBLISHED, publishedDate),
        publisher = pick(AbsField.PUBLISHER, publisher),
        description = pick(AbsField.DESCRIPTION, description),
        isbn = pick(AbsField.ISBN, isbn),
        language = pick(AbsField.LANGUAGE, language),
      ),
      tags = tags.takeIf { it.isNotEmpty() },
    )
  }

  @Serializable
  private class MetadataFile(
    val title: String,
    val subtitle: String?,
    val authors: List<String>,
    val narrators: List<String>,
    val series: List<String>,
    val genres: List<String>,
    val tags: List<String>,
    val publishedYear: String?,
    val publishedDate: String?,
    val publisher: String?,
    val description: String?,
    val isbn: String?,
    val language: String?,
    val chapters: List<AbsChapter>?,
  )

  @Serializable
  internal class MediaUpdate(val metadata: MetadataUpdate, val tags: List<String>? = null)

  @Serializable
  internal class MetadataUpdate(
    val title: String?,
    val subtitle: String?,
    val authors: List<Name>?,
    val narrators: List<String>?,
    val series: List<Series>?,
    val genres: List<String>?,
    val publishedYear: String?,
    val publishedDate: String?,
    val publisher: String?,
    val description: String?,
    val isbn: String?,
    val language: String?,
  )

  @Serializable
  internal class Name(val name: String)

  @Serializable
  internal class Series(val name: String, val sequence: String?)

  private companion object {
    val metadataJson = Json { prettyPrint = true; explicitNulls = false }
  }
}
