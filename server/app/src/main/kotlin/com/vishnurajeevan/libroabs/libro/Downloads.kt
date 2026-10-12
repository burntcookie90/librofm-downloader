package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.models.server.resolveWithin
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.prepareGet
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.copyAndClose
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Streams [url] into [destination]. Fails unless the server answers with a success status, so an error page (an
 * expired link, say) is never saved as the book, and removes whatever was partially written.
 */
internal suspend fun HttpClient.downloadTo(url: Url, destination: File) {
  try {
    prepareGet(url).execute { response ->
      if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value} downloading from ${url.host}")
      response.body<ByteReadChannel>().copyAndClose(destination.writeChannel())
    }
  } catch (e: Throwable) {
    destination.delete()
    throw e
  }
}

/** Extracts [zip] into [targetDirectory] and returns the files written. An archive without any files is an error. */
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

/** The file name libro.fm's pre-signed M4B links ask for, falling back to the last path segment. */
internal fun m4bFilename(url: Url): String {
  val disposition = url.parameters["response-content-disposition"].orEmpty()
  return "filename=\"?([^\"]+)\"?".toRegex().find(disposition)?.groupValues?.get(1)?.replace("+", " ")
    ?: url.segments.lastOrNull()?.takeIf { it.isNotBlank() }
    ?: "audiobook.m4b"
}
