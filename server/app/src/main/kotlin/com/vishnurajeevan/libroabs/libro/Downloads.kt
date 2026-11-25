package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.models.server.resolveWithin
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/** A download failed in a way that retrying the same URL won't fix (an expired or forbidden link, say). */
class PermanentDownloadException(message: String) : IOException(message)

/** How many times a download is attempted, and how long to wait before attempt `n + 1`. */
class RetryPolicy(
  val attempts: Int = 5,
  val delayMillis: (attempt: Int) -> Long = { attempt -> minOf(30_000L, 1_000L shl (attempt - 1)) },
) {
  companion object {
    val None = RetryPolicy(attempts = 1)
  }
}

/**
 * Streams [url] into [destination].
 *
 * The data goes to a `.part` file next to [destination] that only replaces it once the whole body has arrived, so a
 * failed download never leaves a truncated file behind or destroys a copy that was already there. Dropped
 * connections, timeouts and server errors are retried, resuming from where the previous attempt stopped when the
 * server supports ranges. Error responses (an expired link, say) are never saved as the book.
 */
internal suspend fun HttpClient.downloadTo(url: Url, destination: File, retry: RetryPolicy = RetryPolicy()) {
  val part = File(destination.parentFile, "${destination.name}.part")
  // A leftover from a run that was killed may belong to a different version of the file; start over.
  part.delete()
  try {
    var attempt = 1
    while (true) {
      try {
        downloadAttempt(url, part)
        break
      } catch (e: CancellationException) {
        throw e
      } catch (e: PermanentDownloadException) {
        throw e
      } catch (e: IOException) {
        if (attempt >= retry.attempts) throw IOException("${e.message} (gave up after $attempt attempts)", e)
        delay(retry.delayMillis(attempt))
        attempt++
      }
    }
    Files.move(part.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  } finally {
    part.delete()
  }
}

/** One request for [url], appending to [part] when it already holds the start of the file. */
private suspend fun HttpClient.downloadAttempt(url: Url, part: File) {
  val offset = if (part.exists()) part.length() else 0L
  prepareGet(url) {
    if (offset > 0) header(HttpHeaders.Range, "bytes=$offset-")
  }.execute { response ->
    val status = response.status
    if (!status.isSuccess()) {
      val message = "HTTP ${status.value} downloading from ${url.host}"
      // 408, 429 and 5xx are worth another go; anything else (403 for an expired link, 404) will keep failing.
      if (status == HttpStatusCode.RequestTimeout || status == HttpStatusCode.TooManyRequests || status.value >= 500) {
        throw IOException(message)
      }
      throw PermanentDownloadException(message)
    }
    // The server ignored the range and is sending everything again.
    val resuming = offset > 0 && status == HttpStatusCode.PartialContent
    val expected = response.contentLength()?.let { if (resuming) it + offset else it }
    FileOutputStream(part, resuming).use { out ->
      val channel = response.body<ByteReadChannel>()
      val buffer = ByteArray(64 * 1024)
      while (true) {
        val read = channel.readAvailable(buffer)
        if (read == -1) break
        out.write(buffer, 0, read)
      }
    }
    if (expected != null && part.length() != expected) {
      throw IOException("Download from ${url.host} ended after ${part.length()} of $expected bytes")
    }
  }
}

/**
 * Extracts [zip] into [targetDirectory] and returns the files written. An archive without any files, or one that is
 * cut short, is an error.
 */
internal fun extractZip(zip: File, targetDirectory: File): List<File> {
  val files = mutableListOf<File>()
  ZipInputStream(zip.inputStream()).use { zipIn ->
    var entry = zipIn.nextEntry
    while (entry != null) {
      val target = targetDirectory.resolveWithin(entry.name)
      if (entry.isDirectory) {
        target.mkdirs()
      } else {
        target.parentFile?.mkdirs()
        target.outputStream().use { zipIn.copyTo(it) }
        files += target
      }
      entry = zipIn.nextEntry
    }
  }
  if (files.isEmpty()) throw IOException("${zip.name} contained no files")
  return files
}

/**
 * Moves every file in [staging] into the same relative place under [targetDirectory], replacing what is there, and
 * returns their new locations.
 */
internal fun moveInto(staging: File, files: List<File>, targetDirectory: File): List<File> = files.map { file ->
  val target = targetDirectory.resolveWithin(file.relativeTo(staging).path)
  target.parentFile?.mkdirs()
  Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
  target
}

/** The file name libro.fm's pre-signed M4B links ask for, falling back to the last path segment. */
internal fun m4bFilename(url: Url): String {
  val disposition = url.parameters["response-content-disposition"].orEmpty()
  return "filename=\"?([^\"]+)\"?".toRegex().find(disposition)?.groupValues?.get(1)?.replace("+", " ")
    ?: url.segments.lastOrNull()?.takeIf { it.isNotBlank() }
    ?: "audiobook.m4b"
}
