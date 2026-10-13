package com.vishnurajeevan.libroabs.connector.hardcover

import com.vishnurajeevan.libroabs.connector.ConnectorContributor
import com.vishnurajeevan.libroabs.connector.TrackerCandidate

/** Reads the books out of Hardcover's search results, which come back as untyped JSON. */
internal fun searchCandidates(results: Any?): List<TrackerCandidate> {
  val hits = (results as? Map<*, *>)?.get("hits") as? List<*> ?: return emptyList()
  return hits.mapNotNull { hit ->
    val doc = (hit as? Map<*, *>)?.get("document") as? Map<*, *> ?: return@mapNotNull null
    val id = doc["id"]?.toString() ?: return@mapNotNull null
    val title = doc["title"] as? String ?: return@mapNotNull null
    TrackerCandidate(
      id = id,
      title = title,
      alternativeTitles = (doc["alternative_titles"] as? List<*>).orEmpty().filterIsInstance<String>(),
      authors = (doc["contributions"] as? List<*>).orEmpty().mapNotNull { contribution ->
        contribution as? Map<*, *> ?: return@mapNotNull null
        // Translators, illustrators and the like don't identify the book.
        val role = contribution["contribution"] as? String
        if (role != null && role != "Author") return@mapNotNull null
        val author = contribution["author"] as? Map<*, *> ?: return@mapNotNull null
        val authorId = (author["id"] as? Number)?.toLong()?.toString() ?: return@mapNotNull null
        ConnectorContributor(authorId, author["name"] as? String ?: return@mapNotNull null)
      },
      compilation = doc["compilation"] == true,
      usersCount = (doc["users_count"] as? Number)?.toInt() ?: 0,
      releaseYear = (doc["release_year"] as? Number)?.toInt(),
      url = (doc["slug"] as? String)?.let { "https://hardcover.app/books/$it" },
    )
  }
}
