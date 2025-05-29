package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.DownloadPart
import com.vishnurajeevan.libroabs.models.libro.LoginRequest
import com.vishnurajeevan.libroabs.models.libro.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.models.server.M4bMetadata
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.storage.Storage
import com.vishnurajeevan.libroabs.storage.models.AuthToken
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import com.vishnurajeevan.libroabs.storage.models.WishlistItemSyncStatus
import com.vishnurajeevan.libroabs.storage.models.WishlistSyncHistory
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.outputStream

@Inject
class LibroApiHandler(
  serverInfo: ServerInfo,
  private val libroAPI: LibroAPI,
  @Named("download") private val downloadClient: HttpClient,
  private val authTokenStorage: Storage<AuthToken>,
  private val wishlistSyncHistoryStorage: Storage<WishlistSyncHistory>,
  private val libroLibraryStorage: Storage<LibraryMetadata>,
  private val lfdLogger: Logger,
  @Io private val ioDispatcher: CoroutineDispatcher,
) {
  private val dryRun = serverInfo.dryRun

  suspend fun fetchLoginData(username: String, password: String) = withContext(ioDispatcher) {
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

  suspend fun fetchLibrary(page: Int = 1) = withContext(ioDispatcher) {
    libroLibraryStorage.update { libroAPI.fetchLibrary(authToken = token, page = page) }
  }

  suspend fun getLocalLibrary(): LibraryMetadata = withContext(ioDispatcher) { libroLibraryStorage.getData() }

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
      lfdLogger.log("Downloading M4B: $m4bUrl")
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
        lfdLogger.log("downloading part ${index + 1}")
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

  suspend fun syncWishlist(isbns: List<String>) = withContext(ioDispatcher) {
    isbns.minus(
      fetchWishlist()
        .audiobooks
        .map { it.isbn }
    )
      .minus(
        wishlistSyncHistoryStorage.getData().history.keys
      )
      .forEach { isbn ->
        lfdLogger.log("Syncing wishlist for $isbn")
        val response = libroAPI.addToWishlist(authToken = token, isbn = isbn)
        wishlistSyncHistoryStorage.update {
          val status = if (response.isSuccessful) WishlistItemSyncStatus.SUCCESS else WishlistItemSyncStatus.FAILURE
          it.copy(history = it.history + (isbn to status))
        }
      }
  }

  suspend fun fetchWishlist() = withContext(ioDispatcher) {
    libroAPI.fetchWishlist(token)
      .data
      .wishlist
  }

  suspend fun fetchBookDetails(isbn: String): Book = withContext(ioDispatcher) {
    libroAPI.fetchAudiobookDetails(token, isbn).data.audiobook
  }

  private suspend fun downloadFile(url: Url, destinationFile: File) {
    lfdLogger.log(
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