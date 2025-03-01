package com.vishnurajeevan.libroabs.libro

import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
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
  verbose: Boolean,
) {
  private val ktorfit = Ktorfit.Builder()
    .baseUrl("https://libro.fm/")
    .httpClient(client.config {
      defaultRequest {
        contentType(ContentType.Application.Json)
      }
      install(Logging) {
        logger = object: Logger {
          override fun log(message: String) {
            lfdLogger(message)
          }

        }
        level = if (verbose) LogLevel.BODY else LogLevel.NONE
      }
      install(ContentNegotiation) {
        json(Json {
          isLenient = true
          ignoreUnknownKeys = true
        })
      }
    })
    .build()

  private val downloadClient = client.config {
    install(HttpTimeout) {
      requestTimeoutMillis = 5 * 60 * 1000
    }
    install(Logging) {
      logger = object: Logger {
        override fun log(message: String) {
          lfdLogger(message)
        }

      }
      level = if (verbose) LogLevel.INFO else LogLevel.NONE
    }
  }

  private val libroAPI = ktorfit.createLibroAPI()
  private val authToken by lazy {
    File("$dataDir/token.txt").useLines { it.first() }
  }

  suspend fun fetchLoginData(username: String, password: String) {
    val tokenData = libroAPI.fetchLoginData(
      LoginRequest(username = username, password = password)
    )
    if (tokenData.access_token != null) {
      val file = File("$dataDir/token.txt")
      file.printWriter().use {
        it.write(tokenData.access_token!!)
      }
    }
    else {
      println("Login failed!")
      throw IllegalArgumentException("failed login!")
    }
  }

  suspend fun fetchLibrary(page: Int = 1) {
    val library = libroAPI.fetchLibrary("Bearer $authToken", page)
    if (library.audiobooks.isNotEmpty()) {
      File("$dataDir/library.json").writeText(Json.encodeToString<LibraryMetadata>(library))
    }
  }

  suspend fun fetchDownloadMetadata(isbn: String): DownloadMetadata {
    return libroAPI.fetchDownloadMetadata("Bearer $authToken", isbn)
  }

  suspend fun fetchAudioBook(data: List<DownloadPart>, targetDirectory: File) {
    data.forEachIndexed { index, part ->
      if (!dryRun) {
        val url = part.url
        lfdLogger("downloading part ${index + 1}")
        val destinationFile = File(targetDirectory, "part-$index.zip")
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

        ZipInputStream(destinationFile.inputStream()).use { zipIn ->
          var entry = zipIn.nextEntry
          while (entry != null) {
            val entryPath = targetDirectory.toPath() / entry.name

            if (entry.isDirectory) {
              // Create directory
              entryPath.createDirectories()
            }
            else {
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

  suspend fun renameChapters(
    title: String,
    tracks: List<Tracks>,
    targetDirectory: File,
    writeTitleTag: Boolean
  ) = withContext(Dispatchers.IO) {
    if (tracks.any { it.chapter_title == null }) return@withContext

    val sortedTracks = tracks.sortedBy { it.number }

    val newFilenames = sortedTracks
      .map { track ->
        "${track.number.padToTotal(tracks.size)} - $title - ${track.chapter_title}"
      }

    val trackTitles = sortedTracks
      .map { track ->
        "${track.number.padToTotal(tracks.size)} - ${track.chapter_title}"
      }

    targetDirectory.listFiles()
      ?.sortedBy { it.nameWithoutExtension }
      ?.forEachIndexed({ index, file ->
        val newFilename = newFilenames[index]
        val newFile = File(targetDirectory, "$newFilename.${file.extension}")
        file.renameTo(newFile)

        if (writeTitleTag) {
          val audioFile = AudioFileIO.read(newFile)
          val tag = audioFile.tag
          tag.setField(FieldKey.TITLE, trackTitles[index])
          audioFile.commit()
        }
      })
  }

  suspend fun deleteMp3Files(targetDirectory: File) = withContext(Dispatchers.IO) {
    targetDirectory.listFiles { file -> file.extension == "mp3" }
        ?.forEach { it.delete() }
  }

  private fun Int.padToTotal(total: Int): String {
    return toString().padStart(total.toString().length, '0')
  }
}