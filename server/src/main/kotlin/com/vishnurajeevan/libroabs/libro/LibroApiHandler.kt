package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.LfdLogger
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.Url
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.outputStream
import kotlin.time.Duration.Companion.seconds

@Inject
class LibroApiHandler(
  client: HttpClient,
  private val libroAPI: LibroAPI,
  @Named("dataDir") private val dataDir: String,
  @Named("dryRun") private val dryRun: Boolean,
  private val logger: LfdLogger,
) {
  private val downloadClient = client.config {
    install(HttpTimeout) {
      requestTimeoutMillis = 5 * 60 * 1000
    }
  }

  private val authToken by lazy {
    File("$dataDir/token.txt").useLines { it.first() }
  }

  suspend fun fetchLoginData(username: String, password: String) = withContext(Dispatchers.IO) {
    val tokenData = libroAPI.fetchLoginData(
      LoginRequest(username = username, password = password)
    )
    if (tokenData.access_token != null) {
      val file = File("$dataDir/token.txt")
      file.printWriter().use {
        it.write(tokenData.access_token!!)
      }
    } else {
      println("Login failed!")
      throw IllegalArgumentException("failed login!")
    }
  }

  private val token = "Bearer $authToken"

  suspend fun fetchLibrary(page: Int = 1) = withContext(Dispatchers.IO) {
    val library = libroAPI.fetchLibrary(token, page)
    if (library.audiobooks.isNotEmpty()) {
      File("$dataDir/library.json").writeText(Json.encodeToString<LibraryMetadata>(library))
    }
  }

  suspend fun fetchMp3DownloadMetadata(isbn: String): Mp3DownloadMetadata {
    return libroAPI.fetchDownloadMetadata(token, isbn)
  }

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
      logger.log("Downloading M4B: $m4bUrl")
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
        logger.log("downloading part ${index + 1}")
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
      libroAPI.fetchWishlist(token)
        .data
        .wishlist
        .audiobooks
        .map { it.isbn }
    ).forEach {
      logger.log("Syncing wishlist for $it")
      libroAPI.addToWishlist(token, it)
      delay(3.seconds)
    }
  }

  private suspend fun downloadFile(url: Url, destinationFile: File) {
    logger.log(
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