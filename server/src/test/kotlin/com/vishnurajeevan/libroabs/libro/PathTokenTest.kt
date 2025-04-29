package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.models.createPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class PathTokenTest {
  private val singleAuthorBook = Book(
    title = "The Great Novel",
    authors = listOf("Jane Doe"),
    isbn = "1234567890",
    cover_url = "http://example.com/cover.jpg",
    audiobook_info = BookInfo(
      narrators = listOf("John Smith"),
      duration = 3600,
      track_count = 12
    ),
    publisher = "Test Publisher",
    publication_date = "2023-01-01",
    description = "A great novel",
    genres = listOf(Genre("Fiction")),
    series = "Amazing Series",
    series_num = 1
  )

  private val multipleAuthorsBook = Book(
    title = "Collaborative Work",
    authors = listOf("John Doe", "Jane Smith", "Alex Johnson"),
    isbn = "0987654321",
    cover_url = "http://example.com/cover2.jpg",
    audiobook_info = BookInfo(
      narrators = listOf("Voice Actor"),
      duration = 7200,
      track_count = 24
    ),
    publisher = "Another Publisher",
    publication_date = "2023-02-01",
    description = "Written by multiple authors",
    genres = listOf(Genre("Non-fiction")),
    series = null,
    series_num = null
  )

  private val multipleNarratorsBook = Book(
    title = "Epic Tale",
    authors = listOf("Famous Author"),
    isbn = "5555555555",
    cover_url = "http://example.com/cover3.jpg",
    audiobook_info = BookInfo(
      narrators = listOf("Narrator One", "Narrator Two", "Narrator Three"),
      duration = 10800,
      track_count = 36
    ),
    publisher = "Epic Publisher",
    publication_date = "2023-03-01",
    description = "Narrated by multiple people",
    genres = listOf(Genre("Fantasy")),
    series = "Epic Series",
    series_num = 2
  )

  @Test
  fun testAuthorFirstBookTitle() {
    val pathPattern = "FIRST_AUTHOR/BOOK_TITLE"
    val expected = "Jane Doe/The Great Novel"
    assertEquals(expected, File(singleAuthorBook.createPath(pathPattern)).path)
  }

  @Test
  fun testAuthorAllBookTitle() {
    val pathPattern = "ALL_AUTHORS/BOOK_TITLE"
    val expected = "John Doe, Jane Smith, Alex Johnson/Collaborative Work"
    assertEquals(expected, File(multipleAuthorsBook.createPath(pathPattern)).path)
  }

  @Test
  fun testSeriesNameBookTitle() {
    val pathPattern = "SERIES_NAME/BOOK_TITLE"
    val expected = "Amazing Series/The Great Novel"
    assertEquals(expected, File(singleAuthorBook.createPath(pathPattern)).path)
  }

  @Test
  fun testNarratorFirstBookTitle() {
    val pathPattern = "FIRST_NARRATOR/BOOK_TITLE"
    val expected = "John Smith/The Great Novel"
    assertEquals(expected, File(singleAuthorBook.createPath(pathPattern)).path)
  }

  @Test
  fun testNarratorAllIsbn() {
    val pathPattern = "ALL_NARRATORS/ISBN"
    val expected = "Narrator One, Narrator Two, Narrator Three/5555555555"
    assertEquals(expected, File(multipleNarratorsBook.createPath(pathPattern)).path)
  }

  @Test
  fun testAuthorFirstSeriesNameBookTitle() {
    val pathPattern = "FIRST_AUTHOR/SERIES_NAME/BOOK_TITLE"
    val expected = "Famous Author/Epic Series/Epic Tale"
    assertEquals(expected, File(multipleNarratorsBook.createPath(pathPattern)).path)
  }

  @Test
  fun testComplexPath() {
    val pathPattern = "ALL_AUTHORS/FIRST_NARRATOR/BOOK_TITLE/ISBN"
    val expected = "Jane Doe/John Smith/The Great Novel/1234567890"
    assertEquals(expected, File(singleAuthorBook.createPath(pathPattern)).path)
  }

  @Test
  fun testMultipleAuthorsNarratorsPath() {
    val pathPattern = "ALL_AUTHORS/ALL_NARRATORS/BOOK_TITLE"
    val expected = "Famous Author/Narrator One, Narrator Two, Narrator Three/Epic Tale"
    assertEquals(expected, File(multipleNarratorsBook.createPath(pathPattern)).path)
  }


  @Test
  fun testListOfBooksWithFullPathPattern() {
    val pathPattern = "ALL_AUTHORS/FIRST_AUTHOR/ALL_NARRATORS/FIRST_NARRATOR/SERIES_NAME/ISBN/BOOK_TITLE"

    assertEquals(
      listOf(
        "Jane Doe/Jane Doe/John Smith/John Smith/Amazing Series/1234567890/The Great Novel",
        "John Doe, Jane Smith, Alex Johnson/John Doe/Voice Actor/Voice Actor/0987654321/Collaborative Work",
        "Famous Author/Famous Author/Narrator One, Narrator Two, Narrator Three/Narrator One/Epic Series/5555555555/Epic Tale"
      ),
      listOf(
        singleAuthorBook,
        multipleAuthorsBook,
        multipleNarratorsBook
      ).map {
        File(it.createPath(pathPattern)).path
      }
    )
  }
}
