package com.vishnurajeevan.libroabs.tracker

import com.vishnurajeevan.libroabs.connector.ConnectorContributor
import com.vishnurajeevan.libroabs.connector.TrackerCandidate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookMatcherTest {
  private fun candidate(
    id: String,
    title: String,
    author: String,
    compilation: Boolean = false,
    users: Int = 0,
    alternatives: List<String> = emptyList(),
  ) = TrackerCandidate(id, title, alternatives, listOf(ConnectorContributor("1", author)), compilation, users)

  @Test
  fun ignoresCasePunctuationAndALeadingThe() {
    val match = bestMatch("The Hobbit", listOf("J.R.R. Tolkien"), listOf(candidate("1", "hobbit", "J. R. R. Tolkien")))
    assertEquals("1", match?.candidate?.id)
  }

  @Test
  fun matchesWithoutTheSubtitle() {
    val match = bestMatch(
      "Artificial Condition: The Murderbot Diaries, Book 2",
      listOf("Martha Wells"),
      listOf(candidate("1", "Artificial Condition", "Martha Wells")),
    )
    assertEquals("1", match?.candidate?.id)
    assertEquals("same title apart from the subtitle, same author", match?.reason)
  }

  @Test
  fun usesAlternativeTitles() {
    val match = bestMatch(
      "Murderbot 1",
      listOf("Martha Wells"),
      listOf(candidate("1", "All Systems Red", "Martha Wells", alternatives = listOf("Murderbot 1"))),
    )
    assertEquals("1", match?.candidate?.id)
  }

  @Test
  fun stripsCredentialsFromAuthors() {
    val match = bestMatch("Being Mortal", listOf("Dr. Atul Gawande"), listOf(candidate("1", "Being Mortal", "Atul Gawande")))
    assertEquals("1", match?.candidate?.id)
  }

  @Test
  fun neverTakesTheSameTitleBySomeoneElse() {
    assertNull(bestMatch("Dune", listOf("Frank Herbert"), listOf(candidate("1", "Dune", "Someone Else"))))
  }

  @Test
  fun rejectsADifferentBookBySameAuthor() {
    assertNull(bestMatch("Rogue Protocol", listOf("Martha Wells"), listOf(candidate("1", "Exit Strategy", "Martha Wells"))))
  }

  @Test
  fun prefersTheBookToAnOmnibus() {
    val match = bestMatch(
      "All Systems Red",
      listOf("Martha Wells"),
      listOf(
        candidate("omnibus", "All Systems Red", "Martha Wells", compilation = true, users = 9_000),
        candidate("book", "All Systems Red", "Martha Wells", users = 10),
      ),
    )
    assertEquals("book", match?.candidate?.id)
  }

  @Test
  fun breaksTiesByPopularity() {
    val match = bestMatch(
      "Project Hail Mary",
      listOf("Andy Weir"),
      listOf(candidate("dupe", "Project Hail Mary", "Andy Weir", users = 3), candidate("main", "Project Hail Mary", "Andy Weir", users = 90_000)),
    )
    assertEquals("main", match?.candidate?.id)
  }
}
