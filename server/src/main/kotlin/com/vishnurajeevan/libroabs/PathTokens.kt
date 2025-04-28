package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libroabs.libro.Book

enum class PathTokens {
  FIRST_AUTHOR,
  ALL_AUTHORS,
  SERIES_NAME,
  BOOK_TITLE,
  ISBN,
  FIRST_NARRATOR,
  ALL_NARRATORS
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
      }
    }
    .joinToString("/")
}

