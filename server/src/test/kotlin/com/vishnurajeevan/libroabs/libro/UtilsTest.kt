package com.vishnurajeevan.libroabs.libro

import kotlin.test.Test
import kotlin.test.assertEquals

class UtilsTest {
  @Test
  fun `createFilenames with single track`() {
    val tracks = listOf(
      Tracks(number = 1, chapter_title = "Chapter One")
    )
    val title = "Book Title"

    val expected = listOf("1 - Book Title - Chapter One")
    assertEquals(expected, createFilenames(tracks, title))
  }

  @Test
  fun `createFilenames with multiple tracks`() {
    val tracks = listOf(
      Tracks(number = 1, chapter_title = "Chapter One"),
      Tracks(number = 2, chapter_title = "Chapter Two"),
      Tracks(number = 3, chapter_title = "Chapter Three")
    )
    val title = "Book Title"

    val expected = listOf(
      "1 - Book Title - Chapter One",
      "2 - Book Title - Chapter Two",
      "3 - Book Title - Chapter Three"
    )
    assertEquals(expected, createFilenames(tracks, title))
  }

  @Test
  fun `createFilenames with double digit numbers`() {
    val tracks = (1..10).map { number ->
      Tracks(number = number, chapter_title = "Chapter $number")
    }
    val title = "Book Title"

    val expected = listOf(
      "01 - Book Title - Chapter 1",
      "02 - Book Title - Chapter 2",
      "03 - Book Title - Chapter 3",
      "04 - Book Title - Chapter 4",
      "05 - Book Title - Chapter 5",
      "06 - Book Title - Chapter 6",
      "07 - Book Title - Chapter 7",
      "08 - Book Title - Chapter 8",
      "09 - Book Title - Chapter 9",
      "10 - Book Title - Chapter 10"
    )
    assertEquals(expected, createFilenames(tracks, title))
  }

  @Test
  fun `createTrackTitles with single track`() {
    val tracks = listOf(
      Tracks(number = 1, chapter_title = "Chapter One")
    )

    val expected = listOf("1 - Chapter One")
    assertEquals(expected, createTrackTitles(tracks))
  }

  @Test
  fun `createTrackTitles with multiple tracks`() {
    val tracks = listOf(
      Tracks(number = 1, chapter_title = "Chapter One"),
      Tracks(number = 2, chapter_title = "Chapter Two"),
      Tracks(number = 3, chapter_title = "Chapter Three")
    )

    val expected = listOf(
      "1 - Chapter One",
      "2 - Chapter Two",
      "3 - Chapter Three"
    )
    assertEquals(expected, createTrackTitles(tracks))
  }

  @Test
  fun `createTrackTitles with double digit numbers`() {
    val tracks = (1..10).map { number ->
      Tracks(number = number, chapter_title = "Chapter $number")
    }

    val expected = listOf(
      "01 - Chapter 1",
      "02 - Chapter 2",
      "03 - Chapter 3",
      "04 - Chapter 4",
      "05 - Chapter 5",
      "06 - Chapter 6",
      "07 - Chapter 7",
      "08 - Chapter 8",
      "09 - Chapter 9",
      "10 - Chapter 10"
    )
    assertEquals(expected, createTrackTitles(tracks))
  }

  @Test
  fun `null chapter titles`() {
    val tracks = listOf(
      Tracks(number = 1, chapter_title = null),
      Tracks(number = 2, chapter_title = null)
    )
    val title = "Book Title"

    val expectedFilenames = listOf(
      "1 - Book Title - null",
      "2 - Book Title - null"
    )
    val expectedTrackTitles = listOf(
      "1 - null",
      "2 - null"
    )

    assertEquals(expectedFilenames, createFilenames(tracks, title))
    assertEquals(expectedTrackTitles, createTrackTitles(tracks))
  }

  @Test
  fun `createFilenames with problematic characters and escaped quotes`() {
    val tracks = listOf(
      Tracks(number = 1, chapter_title = "Chapter 1: \"Hello/World\""),
      Tracks(number = 2, chapter_title = "Chapter 2: <Test>*File?")
    )
    val title = "Book: A \"Story\"/Test"

    val expected = listOf(
      """1 - Book A "Story"Test - Chapter 1 "HelloWorld"""",
      """2 - Book A "Story"Test - Chapter 2 TestFile"""
    )
    assertEquals(expected, createFilenames(tracks, title))
  }

  @Test
  fun `createFilenames with problematic characters and escaped quotes 2`() {
    val tracks = listOf(
      Tracks(number = 1, chapter_title = "Chapter 1: \"Hello/World\""),
      Tracks(number = 2, chapter_title = "Chapter 2: <Test>*File?")
    )
    val title = "Book: A \"Story\" Test"

    val expected = listOf(
      """1 - Book A "Story" Test - Chapter 1 "HelloWorld"""",
      """2 - Book A "Story" Test - Chapter 2 TestFile"""
    )
    assertEquals(expected, createFilenames(tracks, title))
  }

  @Test
  fun `createFilenames with complex chapter title`() {
    val tracks = listOf(
      Tracks(
        number = 1,
        chapter_title = "Chapter 1: LOC/LOM: Clarke Station Population: 23: Days to Ryugu Departure"
      ),
    )
    val title = "Critical Mass"

    val expected = listOf(
      """1 - Critical Mass - Chapter 1 LOCLOM Clarke Station Population 23 Days to Ryugu Departure""",
    )
    assertEquals(expected, createFilenames(tracks, title))
  }
}