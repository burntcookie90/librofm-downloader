package com.vishnurajeevan.libroabs.connector.hardcover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BookMatchingTest {

  private data class Book(
    val title: String?,
    val authors: List<String>,
    val alternativeTitles: Any? = null,
  )

  private fun match(title: String, author: String, vararg candidates: Book) =
    BookMatching.bestMatch(
      title = title,
      author = author,
      candidates = candidates.toList(),
      titlesOf = { listOfNotNull(it.title) + BookMatching.extractTitles(it.alternativeTitles) },
      authorsOf = { it.authors },
    )

  // Formatting differences that the previous exact `_eq` filter could never match.

  @Test
  fun `titles match across case punctuation and accents`() {
    assertTrue(BookMatching.titleMatches("The Wind-Up Bird Chronicle", "the wind up bird chronicle"))
    assertTrue(BookMatching.titleMatches("Les Misérables", "Les Miserables"))
    assertTrue(BookMatching.titleMatches("Cloud Atlas.", "Cloud Atlas"))
  }

  @Test
  fun `titles match across ampersand spelling`() {
    assertTrue(BookMatching.titleMatches("Crime & Punishment", "Crime and Punishment"))
  }

  @Test
  fun `title matches when one side carries a subtitle`() {
    assertTrue(BookMatching.titleMatches("Circe", "Circe: A Novel"))
    assertTrue(BookMatching.titleMatches("Educated: A Memoir", "Educated"))
  }

  // Precision guards. Search is fuzzy and a false positive writes a bad edition to Hardcover.

  @Test
  fun `title does not match a different book sharing a prefix`() {
    assertFalse(BookMatching.titleMatches("Dune", "Dune Messiah"))
  }

  @Test
  fun `title does not match an unrelated title`() {
    assertFalse(BookMatching.titleMatches("Piranesi", "Jonathan Strange & Mr Norrell"))
  }

  @Test
  fun `blank titles never match`() {
    assertFalse(BookMatching.titleMatches("", ""))
    assertFalse(BookMatching.titleMatches("Circe", "   "))
  }

  // Author handling.

  @Test
  fun `authors match across initial spacing`() {
    assertTrue(BookMatching.authorMatches("J.R.R. Tolkien", listOf("J R R Tolkien")))
  }

  @Test
  fun `authors match regardless of name order`() {
    assertTrue(BookMatching.authorMatches("Stephen King", listOf("King, Stephen")))
  }

  @Test
  fun `authors match when one side omits a middle name`() {
    assertTrue(BookMatching.authorMatches("Ursula Le Guin", listOf("Ursula K. Le Guin")))
  }

  @Test
  fun `authors match against any contributor`() {
    assertTrue(BookMatching.authorMatches("Terry Pratchett", listOf("Neil Gaiman", "Terry Pratchett")))
  }

  @Test
  fun `different authors do not match`() {
    assertFalse(BookMatching.authorMatches("Emily St. John Mandel", listOf("Emily Henry")))
  }

  // End to end selection.

  @Test
  fun `returns the first candidate passing verification preserving search ranking`() {
    val expected = Book("Circe: A Novel", listOf("Madeline Miller"))
    val result = match(
      "Circe",
      "Madeline Miller",
      Book("Circe: The Graphic Novel Companion", listOf("Someone Else")),
      expected,
      Book("Circe", listOf("Madeline Miller")),
    )
    assertEquals(expected, result)
  }

  @Test
  fun `returns null when only the title matches`() {
    assertNull(match("Circe", "Madeline Miller", Book("Circe", listOf("Not The Author"))))
  }

  @Test
  fun `returns null when only the author matches`() {
    assertNull(match("Circe", "Madeline Miller", Book("The Song of Achilles", listOf("Madeline Miller"))))
  }

  @Test
  fun `returns null for no candidates`() {
    assertNull(match("Circe", "Madeline Miller"))
  }

  @Test
  fun `tolerates a null title on a candidate`() {
    val expected = Book("Circe", listOf("Madeline Miller"))
    assertEquals(expected, match("Circe", "Madeline Miller", Book(null, listOf("Madeline Miller")), expected))
  }

  // Cross-language matching. Hardcover's book records are the English work, so a translated
  // audiobook can only ever match through alternative_titles.

  @Test
  fun `matches a translated title through alternative titles`() {
    val work = Book(
      title = "The Name of the Wind",
      authors = listOf("Patrick Rothfuss"),
      alternativeTitles = listOf("El nombre del viento", "Der Name des Windes"),
    )
    assertEquals(work, match("El nombre del viento", "Patrick Rothfuss", work))
  }

  @Test
  fun `alternative titles still require the author to match`() {
    val work = Book(
      title = "The Name of the Wind",
      authors = listOf("Patrick Rothfuss"),
      alternativeTitles = listOf("El nombre del viento"),
    )
    assertNull(match("El nombre del viento", "Someone Else", work))
  }

  @Test
  fun `alternative titles are normalized like primary titles`() {
    val work = Book(
      title = "Book",
      authors = listOf("An Author"),
      alternativeTitles = listOf("L'Étranger"),
    )
    assertEquals(work, match("L Etranger", "An Author", work))
  }

  // extractTitles has to survive whatever shape the json column arrives in.

  @Test
  fun `extractTitles reads a flat array`() {
    assertEquals(listOf("one", "two"), BookMatching.extractTitles(listOf("one", "two")))
  }

  @Test
  fun `extractTitles reads objects keyed by language`() {
    assertEquals(
      listOf("El nombre del viento"),
      BookMatching.extractTitles(listOf(mapOf("es" to "El nombre del viento"))),
    )
  }

  @Test
  fun `extractTitles reads a bare string`() {
    assertEquals(listOf("solo"), BookMatching.extractTitles("solo"))
  }

  @Test
  fun `extractTitles drops blanks non-strings and duplicates`() {
    assertEquals(
      listOf("real"),
      BookMatching.extractTitles(listOf("real", "  ", 42, null, "real")),
    )
  }

  @Test
  fun `extractTitles handles null and unexpected shapes`() {
    assertEquals(emptyList(), BookMatching.extractTitles(null))
    assertEquals(emptyList(), BookMatching.extractTitles(42))
  }
}
