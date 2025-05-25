package com.vishnurajeevan.libroabs.models.server

import com.vishnurajeevan.libroabs.models.libro.Book
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

enum class PathTokens {
  FIRST_AUTHOR,
  ALL_AUTHORS,
  SERIES_NAME,
  BOOK_TITLE,
  ISBN,
  FIRST_NARRATOR,
  ALL_NARRATORS,
  PUBLICATION_YEAR,
  PUBLICATION_MONTH,
  PUBLICATION_DAY,
}

fun Book.createPath(pathPattern: String): String {
  return pathPattern.split("/")
    .mapNotNull {
      when(PathTokens.valueOf(it)) {
        PathTokens.FIRST_AUTHOR -> authors.first()
        PathTokens.ALL_AUTHORS -> authors.joinToString(", ")
        PathTokens.SERIES_NAME -> series
        PathTokens.BOOK_TITLE -> title
        PathTokens.ISBN -> isbn
        PathTokens.FIRST_NARRATOR -> audiobook_info.narrators.first()
        PathTokens.ALL_NARRATORS -> audiobook_info.narrators.joinToString(", ")
        PathTokens.PUBLICATION_YEAR -> publication_date.toLocalDateTime(TimeZone.UTC).year
        PathTokens.PUBLICATION_MONTH -> publication_date.toLocalDateTime(TimeZone.UTC).month.number
        PathTokens.PUBLICATION_DAY -> publication_date.toLocalDateTime(TimeZone.UTC).dayOfMonth
      }
    }
    .joinToString("/")
}

