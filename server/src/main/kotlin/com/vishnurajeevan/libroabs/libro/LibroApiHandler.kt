package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.ffmpeg.M4bMetadata
import com.vishnurajeevan.libroabs.libro.models.Book
import com.vishnurajeevan.libroabs.libro.models.DownloadPart
import com.vishnurajeevan.libroabs.libro.models.LibraryMetadata
import com.vishnurajeevan.libroabs.libro.models.LoginRequest
import com.vishnurajeevan.libroabs.libro.models.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.libro.models.storage.AuthToken
import com.vishnurajeevan.libroabs.libro.models.storage.WishlistItemSyncStatus
import com.vishnurajeevan.libroabs.libro.models.storage.WishlistSyncHistory
import com.vishnurajeevan.libroabs.storage.RealStorage
import com.vishnurajeevan.libroabs.storage.Storage
import de.jensklingenberg.ktorfit.Ktorfit
import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.outputStream

class LibroApiHandler(
  client: HttpClient,
  private val dataDir: String,
  private val dryRun: Boolean,
  private val lfdLogger: (String) -> Unit = {},
) {
  private val ktorfit: Ktorfit = Ktorfit.Builder()
    .baseUrl("https://libro.fm/")
    .httpClient(client.config {
      defaultRequest {
        contentType(ContentType.Application.Json)
      }
      install(ContentNegotiation) {
        json(Json {
          isLenient = true
          ignoreUnknownKeys = true
        })
      }
    })
    .converterFactories(ResponseConverterFactory())
    .build()

  private val downloadClient = client.config {
    install(HttpTimeout) {
      requestTimeoutMillis = 5 * 60 * 1000
    }
  }

  private val libroAPI = ktorfit.createLibroAPI()

  private val authTokenStorage: Storage<AuthToken> = RealStorage.Factory<AuthToken>()
    .create(
      file = File("$dataDir/auth_token.json"),
      initial = AuthToken(),
      serializer = serializer(),
      dispatcher = Dispatchers.IO,
      logger = lfdLogger
    )

  private val wishlistSyncHistoryStorage: Storage<WishlistSyncHistory> = RealStorage.Factory<WishlistSyncHistory>()
    .create(
      file = File("$dataDir/wishlist_sync_history.json"),
      initial = WishlistSyncHistory(emptyMap()),
      serializer = serializer(),
      dispatcher = Dispatchers.IO,
      logger = lfdLogger
    )

  private val libroLibraryStorage: Storage<LibraryMetadata> = RealStorage.Factory<LibraryMetadata>()
    .create(
      file = File("$dataDir/libro_library.json"),
      initial = LibraryMetadata(),
      serializer = serializer(),
      dispatcher = Dispatchers.IO,
      logger = lfdLogger
    )

  suspend fun fetchLoginData(username: String, password: String) = withContext(Dispatchers.IO) {
    if (authTokenStorage.getData().token.isNullOrEmpty()) {
      val tokenData = libroAPI.fetchLoginData(
        LoginRequest(username = username, password = password)
      )
      if (tokenData.access_token != null) {
        authTokenStorage.update {
          it.copy(
            token = tokenData.access_token
          )
        }
      } else {
        println("Login failed!")
        throw IllegalArgumentException("failed login!")
      }
    }
  }

  private val token by lazy { runBlocking { "Bearer ${authTokenStorage.getData().token}" } }

  suspend fun fetchLibrary(page: Int = 1) = withContext(Dispatchers.IO) {
    libroLibraryStorage.update { libroAPI.fetchLibrary(authToken = token, page = page) }
  }

  suspend fun getLocalLibrary(): LibraryMetadata = withContext(Dispatchers.IO) { libroLibraryStorage.getData() }

  suspend fun fetchMp3DownloadMetadata(isbn: String): Mp3DownloadMetadata = libroAPI.fetchDownloadMetadata(token, isbn)

  suspend fun fetchM4bMetadata(isbn: String): Result<M4bMetadata> {
    val response = libroAPI.fetchM4BMetadata(token, isbn)
    return if (response.isSuccessful) {
      Result.success(response.body()!!)
    } else {
      Result.failure(Exception("M4B Not Found!"))
    }
  }

  suspend fun downloadM4b(m4bUrl: String, targetDirectory: File) {
    if (!dryRun) {
      lfdLogger("Downloading M4B: $m4bUrl")
      val url = Url(m4bUrl)
      val contentDisposition = url.parameters["response-content-disposition"]!!

      val filenameRegex = "filename=\"?([^\"]+)\"?".toRegex()
      val match = filenameRegex.find(contentDisposition)

      val filename = match?.groupValues?.getOrNull(1)?.replace("+", " ")
      downloadFile(url, File(targetDirectory, filename!!))
    }
  }

  suspend fun downloadMp3s(data: List<DownloadPart>, targetDirectory: File) {
    data.forEachIndexed { index, part ->
      if (!dryRun) {
        val url = part.url
        lfdLogger("downloading part ${index + 1}")
        val destinationFile = File(targetDirectory, "part-$index.zip")
        downloadFile(Url(url), destinationFile)

        ZipInputStream(destinationFile.inputStream()).use { zipIn ->
          var entry = zipIn.nextEntry
          while (entry != null) {
            val entryPath = targetDirectory.toPath() / entry.name

            if (entry.isDirectory) {
              // Create directory
              entryPath.createDirectories()
            } else {
              // Ensure parent directory exists
              entryPath.parent?.createDirectories()

              // Extract file
              entryPath.outputStream().use { output ->
                zipIn.copyTo(output)
              }
            }

            // Move to next entry
            entry = zipIn.nextEntry
          }
        }
        destinationFile.delete()
      }
    }
  }

  suspend fun syncWishlist(isbns: List<String>) = withContext(Dispatchers.IO) {
    isbns.minus(
      fetchWishlist()
        .audiobooks
        .map { it.isbn }
    )
      .minus(
        wishlistSyncHistoryStorage.getData().history.keys
      )
      .forEach { isbn ->
        lfdLogger("Syncing wishlist for $isbn")
        val response = libroAPI.addToWishlist(authToken = token, isbn = isbn)
        wishlistSyncHistoryStorage.update {
          val status = if (response.isSuccessful) WishlistItemSyncStatus.SUCCESS else WishlistItemSyncStatus.FAILURE
          it.copy(history = it.history + (isbn to status))
        }
      }
  }

  suspend fun fetchWishlist() = withContext(Dispatchers.IO) {
    libroAPI.fetchWishlist(token)
      .data
      .wishlist
  }

  suspend fun fetchBookDetails(isbn: String): Book = withContext(Dispatchers.IO) {
    libroAPI.fetchAudiobookDetails(token, isbn).data.audiobook
  }

  private suspend fun downloadFile(url: Url, destinationFile: File) {
    lfdLogger(
      """
      ----
      Downloading $url to ${destinationFile.name}
      ----
    """.trimIndent()
    )
    val response = downloadClient.get(url)

    val input = response.body<ByteReadChannel>()
    FileOutputStream(destinationFile).use { output ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

      while (true) {
        val bytesRead = input.readAvailable(buffer)
        if (bytesRead == -1) break

        output.write(buffer, 0, bytesRead)
      }
      output.flush()
    }
  }
}