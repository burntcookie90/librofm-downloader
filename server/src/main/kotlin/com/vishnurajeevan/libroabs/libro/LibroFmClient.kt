package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.ApplicationLogLevel
import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
  logLevel: ApplicationLogLevel,
) {
  private val ktorfit = Ktorfit.Builder()
    .baseUrl("https://libro.fm/")
    .httpClient(client.config {
      defaultRequest {
        contentType(ContentType.Application.Json)
      }
      install(Logging) {
        logger = object : Logger {
          override fun log(message: String) {
            lfdLogger(message)
          }

        }
        level = when(logLevel) {
          ApplicationLogLevel.NONE -> LogLevel.NONE
          ApplicationLogLevel.INFO -> LogLevel.INFO
          ApplicationLogLevel.VERBOSE -> LogLevel.ALL
        }
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
      logger = object : Logger {
        override fun log(message: String) {
          lfdLogger(message)
        }

      }
      level = when(logLevel) {
        ApplicationLogLevel.NONE -> LogLevel.NONE
        ApplicationLogLevel.INFO -> LogLevel.INFO
        ApplicationLogLevel.VERBOSE -> LogLevel.ALL
      }
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
    } else {
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

  suspend fun fetchMp3DownloadMetadata(isbn: String): Mp3DownloadMetadata {
    return libroAPI.fetchDownloadMetadata("Bearer $authToken", isbn)
  }

  suspend fun fetchM4bMetadata(isbn: String): M4bMetadata = libroAPI.fetchM4BMetadata("Bearer $authToken", isbn)

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

  private suspend fun downloadFile(url: Url, destinationFile: File) {
    lfdLogger("""
      ----
      Downloading $url to ${destinationFile.name}
      ----
    """.trimIndent())
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

  suspend fun renameChapters(
    title: String,
    tracks: List<Tracks>,
    targetDirectory: File,
    writeTitleTag: Boolean
  ) = withContext(Dispatchers.IO) {
    if (tracks.any { it.chapter_title == null }) return@withContext

    val sortedTracks = tracks.sortedBy { it.number }

    val newFilenames = createFilenames(sortedTracks, title)

    val trackTitles = createTrackTitles(sortedTracks)

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
}