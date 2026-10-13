package com.vishnurajeevan.libroabs.download

import com.vishnurajeevan.libroabs.audiobookshelf.LibraryImporter
import com.vishnurajeevan.libroabs.converter.ffmpeg.M4bConverter
import com.vishnurajeevan.libroabs.db.repo.DownloadAttemptRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.DownloadItem
import com.vishnurajeevan.libroabs.db.writer.DownloadPdfExtraItem
import com.vishnurajeevan.libroabs.libro.LibroFmBooks
import com.vishnurajeevan.libroabs.libro.createFilenames
import com.vishnurajeevan.libroabs.libro.createTrackTitles
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.models.libro.Tracks
import com.vishnurajeevan.libroabs.models.server.AttemptStatus
import com.vishnurajeevan.libroabs.models.server.AttemptTrigger
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.DownloadedFormat
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class EnqueueResult(
  val attemptId: Long,
  /** `true` when the book already had a queued/running attempt, so no new one was created. */
  val alreadyActive: Boolean,
  /** Completes with the final status of the attempt. Never throws. */
  val result: Deferred<AttemptStatus>,
)

/**
 * Runs book downloads through a queue limited by the configured parallelism, recording every attempt.
 *
 * A failing book never affects any other: its error is stored on its attempt.
 */
@Inject
@SingleIn(AppScope::class)
class DownloadService(
  private val serverInfo: ServerInfo,
  private val ffmpegClient: M4bConverter,
  private val libroClient: LibroFmBooks,
  @Io private val processingScope: CoroutineScope,
  @Io private val ioDispatcher: CoroutineDispatcher,
  private val processingSemaphore: Semaphore,
  private val lfdLogger: Logger,
  private val attemptRepo: DownloadAttemptRepo,
  private val dbWriter: DbWriter,
  private val targetDir: (Book) -> File,
  private val importer: LibraryImporter,
) {
  private class Running(val attemptId: Long, val job: Job, val result: CompletableDeferred<AttemptStatus>)

  private val lock = Mutex()
  private val running = ConcurrentHashMap<String, Running>()
  private val steps = ConcurrentHashMap<Long, String>()

  /** Human readable description of what an active attempt is doing right now. */
  fun currentStep(attemptId: Long): String? = steps[attemptId]

  /** Fails attempts left over from a previous run of the app. */
  suspend fun recoverInterrupted() {
    val count = attemptRepo.interruptActive("Interrupted by an application restart", System.currentTimeMillis())
    if (count > 0) lfdLogger.i("Marked $count interrupted download(s) as failed")
  }

  /**
   * Queues a download of [book]. If the book already has an active attempt that attempt is returned instead.
   * The download always runs, regardless of the download history.
   */
  suspend fun enqueue(
    book: Book,
    trigger: AttemptTrigger,
    format: BookFormat? = null,
  ): EnqueueResult = lock.withLock {
    running[book.isbn]?.let { return@withLock EnqueueResult(it.attemptId, true, it.result) }

    val attemptId = attemptRepo.create(book.isbn, book.title, trigger, format, System.currentTimeMillis())
    val result = CompletableDeferred<AttemptStatus>()
    val job = processingScope.launch(start = CoroutineStart.LAZY) {
      execute(attemptId, book, trigger, format ?: serverInfo.format, result)
    }
    running[book.isbn] = Running(attemptId, job, result)
    job.start()
    EnqueueResult(attemptId, false, result)
  }

  /** Cancels a queued or running attempt. Returns `false` if it is not active. */
  suspend fun cancel(attemptId: Long): Boolean {
    val job = lock.withLock { running.values.firstOrNull { it.attemptId == attemptId }?.job } ?: return false
    job.cancel()
    return true
  }

  fun isActive(isbn: String): Boolean = running.containsKey(isbn)

  private suspend fun execute(
    attemptId: Long,
    book: Book,
    trigger: AttemptTrigger,
    format: BookFormat,
    result: CompletableDeferred<AttemptStatus>,
  ) {
    var status = AttemptStatus.FAILED
    var error: String? = null
    var downloaded: DownloadedFormat? = null
    try {
      val dir = processingSemaphore.withPermit {
        attemptRepo.markRunning(attemptId, System.currentTimeMillis())
        lfdLogger.v("Downloading ${book.title}")
        val dir = targetDir(book).also { it.mkdirs() }
        downloaded = downloadBook(attemptId, book, dir, format)
        withContext(NonCancellable) {
          dbWriter.write(DownloadItem(isbn = book.isbn, format = downloaded!!, path = dir.path))
        }
        // Scheduled runs download extras for the whole library afterwards, so only do it here when forced.
        if (trigger != AttemptTrigger.SCHEDULED && serverInfo.downloadExtras) {
          try {
            steps[attemptId] = "Downloading extras"
            downloadExtras(book)
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            error = "Book downloaded, but extras failed: ${e.describe()}"
          }
        }
        dir
      }
      // Outside the download slot: waiting on Audiobookshelf shouldn't hold up the next book.
      if (importer.enabled) {
        try {
          steps[attemptId] = "Adding to Audiobookshelf"
          importer.import(book, dir)
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          val failure = "Book downloaded, but adding it to Audiobookshelf failed: ${e.describe()}"
          lfdLogger.i("${book.title}: $failure")
          error = listOfNotNull(error, failure).joinToString("; ")
        }
      }
      status = AttemptStatus.SUCCEEDED
    } catch (e: CancellationException) {
      status = AttemptStatus.CANCELLED
      error = "Cancelled"
    } catch (e: Throwable) {
      status = AttemptStatus.FAILED
      error = e.describe()
      lfdLogger.i("Download of ${book.title} (${book.isbn}) failed: $error")
    } finally {
      withContext(NonCancellable) {
        try {
          attemptRepo.markFinished(attemptId, status, error, downloaded, System.currentTimeMillis())
        } finally {
          steps.remove(attemptId)
          lock.withLock { running.remove(book.isbn) }
          result.complete(status)
        }
      }
    }
  }

  /** Downloads the PDF extras of [book] and records that they were fetched. */
  suspend fun downloadExtras(book: Book) {
    val dir = targetDir(book).also { it.mkdirs() }
    libroClient.downloadPdfExtras(
      isbn = book.isbn,
      data = book.audiobook_info.pdf_extras,
      targetDirectory = dir
    )
    withContext(NonCancellable) {
      dbWriter.write(DownloadPdfExtraItem(isbn = book.isbn))
    }
  }

  private suspend fun downloadBook(
    attemptId: Long,
    book: Book,
    targetDir: File,
    format: BookFormat,
  ): DownloadedFormat {
    fun step(text: String) {
      steps[attemptId] = text
    }
    return when (format) {
      BookFormat.MP3 -> {
        step("Downloading MP3s")
        downloadMp3sAndRename(book, targetDir)
        DownloadedFormat.MP3
      }

      BookFormat.M4B_MP3_FALLBACK -> {
        step("Downloading M4B")
        if (downloadBookAsM4b(book, targetDir).isSuccess) {
          DownloadedFormat.M4B
        } else {
          lfdLogger.v("M4B download for ${book.title} failed, falling back to MP3")
          step("No M4B available, downloading MP3s")
          downloadMp3sAndRename(book, targetDir)
          DownloadedFormat.MP3
        }
      }

      BookFormat.M4B_CONVERT_FALLBACK -> {
        step("Downloading M4B")
        if (downloadBookAsM4b(book, targetDir).isSuccess) {
          DownloadedFormat.M4B
        } else {
          lfdLogger.v("M4B download for ${book.title} failed, falling back to conversion")
          step("No M4B available, downloading MP3s")
          downloadMp3sAndRename(book, targetDir)
          step("Converting to M4B")
          convertBookToM4b(book)
          DownloadedFormat.M4B_CONVERTED
        }
      }
    }
  }

  private suspend fun downloadMp3sAndRename(book: Book, targetDir: File) {
    val downloadData = libroClient.fetchMp3DownloadMetadata(book.isbn)
    val files = libroClient.downloadMp3s(data = downloadData.parts, targetDirectory = targetDir)

    if (serverInfo.renameChapters) {
      renameChapters(
        title = book.title,
        tracks = downloadData.tracks,
        files = files,
        targetDirectory = targetDir,
        writeTitleTag = serverInfo.writeTitleTag
      )
    }
  }

  private suspend fun downloadBookAsMp3s(
    book: Book,
    targetDir: File
  ): Mp3DownloadMetadata {
    val downloadData = libroClient.fetchMp3DownloadMetadata(book.isbn)
    libroClient.downloadMp3s(
      data = downloadData.parts,
      targetDirectory = targetDir
    )
    return downloadData
  }

  private suspend fun downloadBookAsM4b(
    book: Book,
    targetDir: File
  ): Result<Unit> {
    val m4bMetadata = libroClient.fetchM4bMetadata(book.isbn)
    if (m4bMetadata.isSuccess) {
      libroClient.downloadM4b(m4bMetadata.getOrThrow().m4b_url, targetDir)
      return Result.success(Unit)
    } else {
      return Result.failure(Exception("M4B Not Found"))
    }
  }

  private suspend fun convertBookToM4b(book: Book) {
    val targetDir = targetDir(book)
    var downloadMetaData: Mp3DownloadMetadata? = null

    // Check that book is downloaded and Mp3s are present
    if (!targetDir.exists()) {
      lfdLogger.v("Book ${book.title} is not downloaded yet!")
      targetDir.mkdirs()
      downloadMetaData = downloadBookAsMp3s(book, targetDir)
    }

    val chapterFiles =
      targetDir.listFiles { file -> file.extension == "mp3" }
    if (chapterFiles == null || chapterFiles.isEmpty()) {
      lfdLogger.v("Book ${book.title} does not have mp3 files downloaded. Downloading the book again.")
      downloadMetaData = downloadBookAsMp3s(book, targetDir)
    }

    if (downloadMetaData == null) {
      downloadMetaData = libroClient.fetchMp3DownloadMetadata(book.isbn)
    }

    lfdLogger.v("Converting ${book.title} from mp3 to m4b.")

    if (!serverInfo.dryRun) {
      ffmpegClient.convertBookToM4b(
        book = book,
        tracks = downloadMetaData.tracks,
        targetDirectory = targetDir,
        audioQuality = serverInfo.audioQuality
      )

      lfdLogger.v("Deleting obsolete mp3 files for ${book.title}")

      deleteMp3Files(targetDir)
    }
  }

  private suspend fun deleteMp3Files(targetDirectory: File) = withContext(Dispatchers.IO) {
    targetDirectory.listFiles { file -> file.extension == "mp3" }
      ?.forEach { it.delete() }
  }

  /** Renames the chapter [files] just extracted for a book; anything else in the folder is left alone. */
  private suspend fun renameChapters(
    title: String,
    tracks: List<Tracks>,
    files: List<File>,
    targetDirectory: File,
    writeTitleTag: Boolean
  ) = withContext(ioDispatcher) {
    if (tracks.any { it.chapter_title == null }) return@withContext

    val sortedTracks = tracks.sortedBy { it.number }

    val newFilenames = createFilenames(sortedTracks, title)

    val trackTitles = createTrackTitles(sortedTracks)

    val chapters = files.filter { it.extension.equals("mp3", ignoreCase = true) }.sortedBy { it.nameWithoutExtension }
    if (chapters.size != sortedTracks.size) {
      lfdLogger.i("Not renaming chapters of $title: ${chapters.size} MP3s but ${sortedTracks.size} tracks")
      return@withContext
    }

    chapters
      .forEachIndexed({ index, file ->
        val newFilename = newFilenames[index]
        val newFile = File(targetDirectory, "$newFilename.${file.extension}")
        Files.move(file.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

        if (writeTitleTag) {
          val audioFile = AudioFileIO.read(newFile)
          val tag = audioFile.tag
          tag.setField(FieldKey.TITLE, trackTitles[index])
          audioFile.commit()
        }
      })
  }
}
