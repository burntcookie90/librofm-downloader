package com.vishnurajeevan.libroabs.audiobookshelf

import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsBookMetadata
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsChapter
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsItemMetadata
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsLibraryItem
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsMedia
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsNamed
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsSeriesRef
import com.vishnurajeevan.libroabs.download.testBook
import com.vishnurajeevan.libroabs.server.ui.MetadataChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AbsMetadataDiffTest {
  private val libro = AbsBookMetadata(
    title = "All Systems Red",
    authors = listOf("Martha Wells"),
    narrators = listOf("Kevin R. Free"),
    series = "Murderbot Diaries",
    seriesSequence = "1",
    genres = listOf("Science Fiction", "Fiction"),
    publisher = "Recorded Books, Inc.",
    publishedDate = "2017-10-20",
    description = "<p>A murderous <b>android</b>.</p>",
    isbn = "9781501977824",
  )

  @Test
  fun listsWhatSyncingWouldChange() {
    val abs = AbsItemMetadata(
      title = "All Systems Red",
      authors = listOf(AbsNamed("Martha Wells")),
      publishedYear = "2017",
      description = "Martha Wells Purchased from Libro.fm.",
    )
    assertEquals(
      listOf(
        MetadataChange("Narrators", "—", "Kevin R. Free", "NARRATORS"),
        MetadataChange("Series", "—", "Murderbot Diaries #1", "SERIES"),
        MetadataChange("Genres", "—", "Science Fiction, Fiction", "GENRES"),
        MetadataChange("Publisher", "—", "Recorded Books, Inc.", "PUBLISHER"),
        MetadataChange("Published", "2017", "2017-10-20", "PUBLISHED"),
        MetadataChange("Description", "Martha Wells Purchased from Libro.fm.", "A murderous android.", "DESCRIPTION"),
        MetadataChange("ISBN", "—", "9781501977824", "ISBN"),
      ),
      metadataChanges(abs, libro),
    )
  }

  @Test
  fun aBookThatAlreadyMatchesHasNoChanges() {
    val abs = AbsItemMetadata(
      title = "All Systems Red",
      authors = listOf(AbsNamed("Martha Wells")),
      narrators = listOf("Kevin R. Free"),
      series = listOf(AbsSeriesRef("Murderbot Diaries", "1")),
      genres = listOf("Fiction", "Science Fiction"),
      publisher = "Recorded Books, Inc. ",
      publishedDate = "2017-10-20",
      description = "<p>A murderous <b>android</b>.</p>",
      isbn = "978-1-5019-7782-4",
    )
    assertEquals(emptyList(), metadataChanges(abs, libro))
  }

  @Test
  fun fieldsLibroDoesntKnowAreLeftAlone() {
    val abs = AbsItemMetadata(title = "All Systems Red", authors = listOf(AbsNamed("Martha Wells")), publisher = "Tor", subtitle = "Murderbot 1")
    val changes = metadataChanges(abs, AbsBookMetadata(title = "All Systems Red", authors = listOf("Martha Wells")))
    assertEquals(emptyList(), changes)
  }

  @Test
  fun chapterNamesChangeOnlyWhenTheCountsMatch() {
    val chapters = (1..4).map { AbsChapter(it - 1, it * 10.0, it * 10.0 + 10, "All Systems Red - Track 00$it") }
    val change = chapterChange(chapters, (1..4).map { "Chapter $it" })!!
    assertEquals("All Systems Red - Track 001, All Systems Red - Track 002, All Systems Red - Track 003, …", change.before)
    assertEquals("Chapter 1, Chapter 2, Chapter 3, …", change.after)
    assertNull(chapterChange(chapters, listOf("Chapter 1")))
    assertNull(chapterChange(chapters, null))
    assertNull(chapterChange(chapters.mapIndexed { i, c -> c.copy(title = "Chapter ${i + 1}") }, (1..4).map { "Chapter $it" }))
    assertTrue(chapters.lookGeneric())
    assertTrue(!listOf(AbsChapter(0, 0.0, 1.0, "Chapter 1")).lookGeneric())
  }

  private fun item(id: String, path: String, title: String, author: String? = null, isbn: String? = null) =
    AbsLibraryItem(id, path, AbsMedia(AbsItemMetadata(title = title, authorName = author, isbn = isbn)))

  @Test
  fun itemsAreMatchedByIsbnThenFolderThenTitleAndAuthor() {
    val book = testBook("9781501977824", "All Systems Red").copy(authors = listOf("Martha Wells"))
    val byTitle = item("t", "/books/Martha Wells/All Systems Red", "All Systems Red", "Martha Wells")
    val byFolder = item("f", "/books/libro/All Systems Red", "Murderbot 1")
    val byIsbn = item("i", "/books/elsewhere", "Something", isbn = "978-1501977824")
    val otherAuthor = item("o", "/books/x", "All Systems Red", "Someone Else")

    assertEquals("i" to "ISBN", matchItem(book, listOf(byTitle, byFolder, byIsbn), "/books/libro/All Systems Red")?.let { it.first.id to it.second })
    assertEquals("f" to "download folder", matchItem(book, listOf(byTitle, byFolder), "/books/libro/All Systems Red/")?.let { it.first.id to it.second })
    assertEquals("t" to "title and author", matchItem(book, listOf(otherAuthor, byTitle), null)?.let { it.first.id to it.second })
    assertNull(matchItem(book, listOf(otherAuthor), null))
  }

  @Test
  fun subtitlesDontStopATitleMatch() {
    val book = testBook("1", "Project Hail Mary: A Novel").copy(authors = listOf("Andy Weir"))
    assertEquals("x", matchItem(book, listOf(item("x", "/p", "Project Hail Mary", "Andy Weir")), null)?.first?.id)
  }

  @Test
  fun credentialsAreNotPartOfNames() {
    assertEquals("Georgia Ede", cleanPersonName("Dr Georgia Ede"))
    assertEquals("Georgia Ede", cleanPersonName("Dr. Georgia Ede"))
    assertEquals("Daniel J. Siegel", cleanPersonName("Daniel J. Siegel, MD"))
    assertEquals("Stacy T. Sims", cleanPersonName("Stacy T. Sims, PhD"))
    assertEquals("Martin Luther King, Jr.", cleanPersonName("Martin Luther King, Jr."))
    assertEquals("Drew Barrymore", cleanPersonName("Drew Barrymore"))
  }

  @Test
  fun existingSeriesAndAuthorSpellingsAreKept() {
    val library = listOf(
      AbsLibraryItem("1", "/a", AbsMedia(AbsItemMetadata(title = "The Eye of the World", authorName = "Robert Jordan", seriesName = "The Wheel of Time #1"))),
      AbsLibraryItem("2", "/b", AbsMedia(AbsItemMetadata(title = "Fourth Wing", authors = listOf(AbsNamed("Rebecca Yarros")), series = listOf(AbsSeriesRef("The Empyrean", "1"))))),
    )
    val wot = AbsBookMetadata(title = "The Great Hunt", authors = listOf("ROBERT JORDAN"), series = "Wheel of Time", seriesSequence = "2")
    assertEquals("The Wheel of Time", wot.spelledLike(library).series)
    assertEquals(listOf("Robert Jordan"), wot.spelledLike(library).authors)
    assertEquals("The Empyrean", wot.copy(series = "Empyrean").spelledLike(library).series)
    assertEquals("Titanium Noir", wot.copy(series = "Titanium Noir").spelledLike(library).series, "unknown series are left as they are")
  }
}
