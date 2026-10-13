package com.vishnurajeevan.libroabs.connector.audiobookshelf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AbsBookMetadataTest {
  private val book = AbsBookMetadata(
    title = "All Systems Red",
    authors = listOf("Martha Wells"),
    narrators = listOf("Kevin R. Free"),
    series = "The Murderbot Diaries",
    seriesSequence = "1",
    genres = listOf("Science Fiction"),
    publishedDate = "2017-05-02",
    isbn = "9781250214706",
  )

  @Test
  fun theMetadataFileUsesAudiobookshelfsFormat() {
    val file = Json.parseToJsonElement(book.toMetadataFile()).jsonObject
    assertEquals("The Murderbot Diaries #1", file["series"]!!.jsonArray.single().jsonPrimitive.content)
    assertEquals("Martha Wells", file["authors"]!!.jsonArray.single().jsonPrimitive.content)
    assertEquals("Kevin R. Free", file["narrators"]!!.jsonArray.single().jsonPrimitive.content)
    assertEquals("2017", file["publishedYear"]!!.jsonPrimitive.content)
    assertEquals("9781250214706", file["isbn"]!!.jsonPrimitive.content)
    assertFalse("publisher" in file, "unknown values are left out rather than written as null")
  }

  @Test
  fun aSeriesWithoutANumberHasNoSequence() {
    val file = Json.parseToJsonElement(book.copy(seriesSequence = null).toMetadataFile()).jsonObject
    assertEquals("The Murderbot Diaries", file["series"]!!.jsonArray.single().jsonPrimitive.content)
  }

  @Test
  fun updatesOnlySendWhatIsKnown() {
    val json = Json { explicitNulls = false }
    val update = json.encodeToString(AbsBookMetadata.MediaUpdate.serializer(), book.copy(series = null, narrators = emptyList()).toMediaUpdate())
    val metadata = Json.parseToJsonElement(update).jsonObject["metadata"]!!.jsonObject
    assertFalse("series" in metadata)
    assertFalse("narrators" in metadata)
    assertEquals("Martha Wells", metadata["authors"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
  }
}
