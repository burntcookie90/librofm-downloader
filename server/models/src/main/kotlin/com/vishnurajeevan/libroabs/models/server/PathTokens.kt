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
  SERIES_NUM,
}

fun PathTokens.convert(book: Book): String {
    return when(this) {
        PathTokens.FIRST_AUTHOR -> book.authors.first().toString()
        PathTokens.ALL_AUTHORS ->  book.authors.joinToString(", ").toString()
        PathTokens.SERIES_NAME ->  book.series?.toString() ?: ""
        PathTokens.BOOK_TITLE ->  book.title.toString()
        PathTokens.ISBN ->  book.isbn.toString()
        PathTokens.FIRST_NARRATOR ->  book.audiobook_info.narrators.first().toString()
        PathTokens.ALL_NARRATORS ->  book.audiobook_info.narrators.joinToString(", ").toString()
        PathTokens.PUBLICATION_YEAR ->  book.publication_date.toLocalDateTime(TimeZone.UTC).year.toString()
        PathTokens.PUBLICATION_MONTH ->  book.publication_date.toLocalDateTime(TimeZone.UTC).month.number.toString()
        PathTokens.PUBLICATION_DAY ->  book.publication_date.toLocalDateTime(TimeZone.UTC).dayOfMonth.toString()
        PathTokens.SERIES_NUM -> book.series_num?.toString() ?: ""
      }
}

private val optionalGroupPattern = Regex("\\{([^}]*?)\\}")

private fun String.resolveOptionalGroups(book: Book): String {
  return optionalGroupPattern.replace(this) { match ->
    var resolved = match.groupValues[1]
    for (token in PathTokens.entries) {
      if (resolved.contains(token.toString())) {
        val value = token.convert(book)
        if (value.isEmpty()) return@replace ""
        resolved = resolved.replace(token.toString(), value)
      }
    }
    resolved
  }
}

fun Book.createPath(pathPattern: String): String {
  return pathPattern
    .split("/")
    .mapNotNull {
      var pathReplace = it.resolveOptionalGroups(this)
      for (token in PathTokens.entries) {
        if (pathReplace.contains(token.toString())) {
          pathReplace = pathReplace.replace(token.toString(), token.convert(this))
        }
      }
      pathReplace.takeIf { it != "" }
    }
    .joinToString("/")
}
