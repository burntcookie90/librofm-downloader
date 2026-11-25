package com.vishnurajeevan.libroabs.connector.audiobookshelf

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.cio.readChannel
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

class AudiobookshelfException(message: String) : IOException(message)

/**
 * The parts of the Audiobookshelf API used to add books and set their details. [apiKey] needs an admin user: adding a
 * single folder is admin-only.
 */
class AudiobookshelfClient(
  client: HttpClient,
  baseUrl: String,
  private val apiKey: String,
) {
  private val api = baseUrl.trimEnd('/') + "/api"

  private val http = client.config {
    expectSuccess = false
    install(ContentNegotiation) {
      json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
    // Uploads can be large; only give up when the connection stalls.
    install(HttpTimeout) {
      connectTimeoutMillis = 30_000
      socketTimeoutMillis = 120_000
    }
    HttpResponseValidator {
      validateResponse { response ->
        if (!response.status.isSuccess()) {
          val request = response.call.request
          val reason = runCatching { response.bodyAsText() }.getOrDefault("").trim().take(200)
          throw AudiobookshelfException(
            "Audiobookshelf answered HTTP ${response.status.value} to ${request.method.value} ${request.url.encodedPath}" +
              if (reason.isNotEmpty() && !reason.startsWith("<")) ": $reason" else ""
          )
        }
      }
    }
  }

  private fun HttpRequestBuilder.auth() = bearerAuth(apiKey)

  private fun HttpRequestBuilder.json(body: Any) {
    contentType(ContentType.Application.Json)
    setBody(body)
  }

  suspend fun libraries(): List<AbsLibrary> =
    http.get("$api/libraries") { auth() }.body<LibrariesResponse>().libraries

  /** Tells Audiobookshelf a file appeared at [path] (as Audiobookshelf sees it), so it adds just that item. */
  suspend fun notifyFileAdded(libraryId: String, path: String) {
    http.post("$api/watcher/update") { auth(); json(WatchedPathUpdate(libraryId, path, "add")) }
  }

  suspend fun scanLibrary(libraryId: String) {
    http.post("$api/libraries/$libraryId/scan") { auth() }
  }

  suspend fun scanItem(itemId: String) {
    http.post("$api/items/$itemId/scan") { auth() }
  }

  suspend fun recentlyAdded(libraryId: String, limit: Int = 25): List<AbsLibraryItem> =
    http.get("$api/libraries/$libraryId/items") {
      auth()
      parameter("sort", "addedAt")
      parameter("desc", 1)
      parameter("limit", limit)
    }.body<ItemsResponse>().results

  /** Every item in the library, a page at a time. */
  suspend fun allItems(libraryId: String, pageSize: Int = 100): List<AbsLibraryItem> {
    val items = mutableListOf<AbsLibraryItem>()
    var page = 0
    while (true) {
      val response = http.get("$api/libraries/$libraryId/items") {
        auth()
        parameter("limit", pageSize)
        parameter("page", page)
      }.body<ItemsResponse>()
      items += response.results
      if (response.results.isEmpty() || items.size >= response.total) return items
      page++
    }
  }

  /** The item's cover image and its content type. Fails when it has none. */
  suspend fun cover(itemId: String): Pair<ByteArray, String> {
    val response = http.get("$api/items/$itemId/cover") { auth() }
    return response.body<ByteArray>() to (response.headers[HttpHeaders.ContentType] ?: "image/jpeg")
  }

  suspend fun search(libraryId: String, query: String, limit: Int = 25): List<AbsLibraryItem> =
    http.get("$api/libraries/$libraryId/search") {
      auth()
      parameter("q", query)
      parameter("limit", limit)
    }.body<SearchResponse>().book.map { it.libraryItem }

  /** The API key's user's listening progress, one entry per item (or podcast episode) they've started. */
  suspend fun mediaProgress(): List<AbsMediaProgress> =
    http.get("$api/me") { auth() }.body<MeResponse>().mediaProgress

  suspend fun item(itemId: String): AbsLibraryItem =
    http.get("$api/items/$itemId") { auth(); parameter("expanded", 1) }.body()

  suspend fun updateMetadata(itemId: String, metadata: AbsBookMetadata, fields: Set<AbsField> = AbsField.entries.toSet()) {
    http.patch("$api/items/$itemId/media") { auth(); json(metadata.toMediaUpdate(fields)) }
  }

  suspend fun uploadCover(itemId: String, cover: File) {
    http.submitFormWithBinaryData("$api/items/$itemId/cover", formData { appendFile("cover", cover) }) { auth() }
  }

  suspend fun updateChapters(itemId: String, chapters: List<AbsChapter>) {
    http.post("$api/items/$itemId/chapters") { auth(); json(ChaptersUpdate(chapters)) }
  }

  /**
   * Uploads [files] as a new book. Audiobookshelf puts them under `<folder>/<author>/<series>/<title>` and does not
   * scan them itself.
   */
  suspend fun upload(
    libraryId: String,
    folderId: String,
    title: String,
    author: String?,
    series: String?,
    files: List<File>,
  ) {
    http.submitFormWithBinaryData(
      "$api/upload",
      formData {
        append("title", title)
        author?.let { append("author", it) }
        series?.let { append("series", it) }
        append("library", libraryId)
        append("folder", folderId)
        files.forEachIndexed { index, file -> appendFile("$index", file) }
      },
    ) { auth() }
  }

  private fun io.ktor.client.request.forms.FormBuilder.appendFile(key: String, file: File) = append(
    key,
    ChannelProvider(file.length()) { file.readChannel() },
    Headers.build {
      // Ktor adds `form-data; name=...` itself; anything but the filename here breaks the part for Audiobookshelf.
      append(HttpHeaders.ContentDisposition, "filename=\"${file.name.replace("\"", "\\\"")}\"")
      append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
    },
  )
}
