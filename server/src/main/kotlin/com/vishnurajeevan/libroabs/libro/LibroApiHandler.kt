package com.vishnurajeevan.libroabs.libro

import de.jensklingenberg.ktorfit.Ktorfit
import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
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
      libroAPI.fetchWishlist(token)
        .data
        .wishlist
        .audiobooks
        .map { it.isbn }
        .also { lfdLogger("Syncing wishlist for $it") }
    ).forEach {
      libroAPI.addToWishlist(token, it)
      delay(3.seconds)
    }
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