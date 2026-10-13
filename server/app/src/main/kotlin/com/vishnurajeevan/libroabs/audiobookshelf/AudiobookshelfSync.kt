package com.vishnurajeevan.libroabs.audiobookshelf

import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsBookMetadata
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsFolder
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsLibrary
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AbsLibraryItem
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AudiobookshelfClient
import com.vishnurajeevan.libroabs.connector.audiobookshelf.AudiobookshelfException
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.server.AbsImportMode
import com.vishnurajeevan.libroabs.models.server.AudiobookshelfConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Gets a downloaded book into Audiobookshelf and gives it libro.fm's metadata, cover and chapter titles.
 *
 * In [AbsImportMode.SHARED] mode Audiobookshelf is told about the new files, so it adds only that book instead of
 * rescanning the library; in [AbsImportMode.UPLOAD] mode the files are uploaded. Either way, if the book hasn't shown
 * up after [scanFallbackAfterMs] (Audiobookshelf's watcher may be off), the library is scanned.
 */
class AudiobookshelfSync(
  private val client: AudiobookshelfClient,
  private val config: AudiobookshelfConfig,
  mediaDir: File,
  private val logger: Logger,
  private val pollIntervalMs: Long = 5_000,
  private val scanFallbackAfterMs: Long = 60_000,
  private val timeoutMs: Long = 10 * 60_000,
) {
  private class Target(val library: AbsLibrary, val folder: AbsFolder, val mediaRoot: String)

  private val mediaDir = mediaDir.canonicalFile
  private val targetLock = Mutex()
  private var target: Target? = null

  suspend fun add(metadata: AbsBookMetadata, directory: File, chapterTitles: List<String>?): AbsLibraryItem {
    val target = target()
    val item = when (config.mode) {
      AbsImportMode.SHARED -> addShared(target, metadata, directory.canonicalFile)
      AbsImportMode.UPLOAD -> addByUpload(target, metadata, directory)
    }
    client.updateMetadata(item.id, metadata)
    File(directory, COVER).takeIf { it.isFile }?.let { client.uploadCover(item.id, it) }
    if (chapterTitles != null) nameChapters(item, chapterTitles)
    return item
  }

  private suspend fun addShared(target: Target, metadata: AbsBookMetadata, directory: File): AbsLibraryItem {
    val relative = directory.relativeToOrNull(mediaDir)?.invariantSeparatorsPath
      ?: throw AudiobookshelfException("$directory is outside the media folder $mediaDir")
    val absPath = "${target.mediaRoot.trimEnd('/')}/$relative"
    val isThisBook = { item: AbsLibraryItem -> item.path.trimEnd('/') == absPath }
    val queries = listOfNotNull(metadata.isbn, metadata.title, directory.name)

    find(target.library.id, queries, isThisBook)?.let { existing ->
      // A re-download: pick up the new files straight away.
      client.scanItem(existing.id)
      return existing
    }
    bookFiles(directory).forEach { file ->
      client.notifyFileAdded(target.library.id, "$absPath/${file.relativeTo(directory).invariantSeparatorsPath}")
    }
    return waitFor(target.library.id, metadata.title, queries, isThisBook)
  }

  private suspend fun addByUpload(target: Target, metadata: AbsBookMetadata, directory: File): AbsLibraryItem {
    val isThisBook = { item: AbsLibraryItem ->
      item.media.metadata.isbn?.let { it == metadata.isbn } ?: item.media.metadata.title.equals(metadata.title, ignoreCase = true)
    }
    val queries = listOfNotNull(metadata.isbn, metadata.title)
    val existing = find(target.library.id, queries, isThisBook)
    // Audiobookshelf files uploads under <folder>/<author>/<series>/<title>. A copy that lives anywhere else would be
    // duplicated by uploading, so it only gets its details updated.
    val uploadPath = listOfNotNull(target.folder.fullPath, metadata.authors.firstOrNull(), metadata.series, metadata.title)
      .joinToString("/")
    if (existing != null && existing.path.comparable() != uploadPath.comparable()) {
      logger.i("${metadata.title} is already in Audiobookshelf at ${existing.path}; updating its details without uploading")
      return existing
    }
    client.upload(
      libraryId = target.library.id,
      folderId = target.folder.id,
      title = metadata.title,
      author = metadata.authors.firstOrNull(),
      series = metadata.series,
      files = bookFiles(directory),
    )
    if (existing != null) {
      client.scanItem(existing.id)
      return existing
    }
    return waitFor(target.library.id, metadata.title, queries, isThisBook)
  }

  private suspend fun waitFor(
    libraryId: String,
    title: String,
    queries: List<String>,
    matches: (AbsLibraryItem) -> Boolean,
  ): AbsLibraryItem {
    var waited = 0L
    var scanned = false
    while (true) {
      find(libraryId, queries, matches)?.let { return it }
      if (!scanned && waited >= scanFallbackAfterMs) {
        logger.i("Audiobookshelf hasn't picked up $title yet, scanning the library")
        client.scanLibrary(libraryId)
        scanned = true
      }
      if (waited >= timeoutMs) {
        throw AudiobookshelfException("Audiobookshelf didn't add $title within ${timeoutMs / 1000} seconds")
      }
      delay(pollIntervalMs)
      waited += pollIntervalMs
    }
  }

  /** New books are among the most recently added; a re-downloaded one has to be searched for. */
  private suspend fun find(
    libraryId: String,
    queries: List<String>,
    matches: (AbsLibraryItem) -> Boolean,
  ): AbsLibraryItem? {
    client.recentlyAdded(libraryId).firstOrNull(matches)?.let { return it }
    for (query in queries.distinct()) {
      client.search(libraryId, query).firstOrNull(matches)?.let { return it }
    }
    return null
  }

  /** libro.fm's chapter names replace Audiobookshelf's "Track 001"s, keeping its timings. */
  private suspend fun nameChapters(item: AbsLibraryItem, titles: List<String>) {
    val chapters = client.item(item.id).media.chapters
    if (chapters.size != titles.size) {
      logger.v("Leaving chapter names alone: Audiobookshelf has ${chapters.size} chapters, libro.fm ${titles.size}")
      return
    }
    client.updateChapters(item.id, chapters.mapIndexed { index, chapter -> chapter.copy(title = titles[index]) })
  }

  private suspend fun target(): Target = targetLock.withLock {
    target ?: resolveTarget().also { target = it }
  }

  private suspend fun resolveTarget(): Target {
    val libraries = client.libraries().filter { it.mediaType == "book" }
    val library = if (config.libraryId != null) {
      libraries.firstOrNull { it.id == config.libraryId }
        ?: throw AudiobookshelfException("Audiobookshelf has no book library with ID ${config.libraryId}")
    } else {
      libraries.singleOrNull() ?: throw AudiobookshelfException(
        if (libraries.isEmpty()) {
          "Audiobookshelf has no book library"
        } else {
          "Audiobookshelf has ${libraries.size} book libraries; set ABS_LIBRARY_ID to one of " +
            libraries.joinToString { "${it.id} (${it.name})" }
        }
      )
    }
    val mediaPath = config.mediaPath?.trimEnd('/')
    val folder = when {
      mediaPath != null -> library.folders.firstOrNull { mediaPath == it.fullPath || mediaPath.startsWith(it.fullPath.trimEnd('/') + "/") }
        ?: throw AudiobookshelfException("ABS_MEDIA_PATH $mediaPath is not inside a folder of the ${library.name} library")
      config.mode == AbsImportMode.UPLOAD -> library.folders.firstOrNull()
      else -> library.folders.singleOrNull()
    } ?: throw AudiobookshelfException(
      if (library.folders.isEmpty()) {
        "The ${library.name} library has no folders"
      } else {
        "The ${library.name} library has ${library.folders.size} folders; set ABS_MEDIA_PATH to where it sees the media folder"
      }
    )
    return Target(library, folder, mediaPath ?: folder.fullPath)
  }

  /** The book's files, without our own temporary ones. */
  private fun bookFiles(directory: File): List<File> = directory.walkTopDown()
    .onEnter { it == directory || !it.name.startsWith(".") }
    .filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") }
    .sortedBy { it.path }
    .toList()

  /** Paths as Audiobookshelf would sanitize them: only letters, digits and separators count. */
  private fun String.comparable() = lowercase().filter { it.isLetterOrDigit() || it == '/' }

  companion object {
    const val COVER = "cover.jpg"
    const val METADATA = "metadata.json"
  }
}
