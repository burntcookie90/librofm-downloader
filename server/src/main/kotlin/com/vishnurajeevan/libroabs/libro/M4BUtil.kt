package com.vishnurajeevan.libroabs.libro

import net.bramp.ffmpeg.FFmpeg
import net.bramp.ffmpeg.FFmpegExecutor
import net.bramp.ffmpeg.FFmpegUtils
import net.bramp.ffmpeg.FFprobe
import net.bramp.ffmpeg.builder.FFmpegBuilder
import net.bramp.ffmpeg.progress.Progress
import net.bramp.ffmpeg.progress.ProgressListener
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

class M4BUtil(
  ffmpegPath: String,
  ffprobePath: String,
  private val lfdLogger: (String) -> Unit = {},
) {

  private val ffmpeg = FFmpeg(ffmpegPath)
  private val ffprobe = FFprobe(ffprobePath)

  suspend fun convertBookToM4b(book: Book, targetDirectory: File) {
    val newFile = File(targetDirectory, "${book.title}.m4b")

    // Download Cover Image
    val coverFile = File(targetDirectory, "cover.jpg")
    downloadCoverImage(book.cover_url, coverFile)

    val chapterFiles =
      targetDirectory.listFiles { file -> file.extension == "mp3" }?.sortedBy { it.nameWithoutExtension }
        ?: emptyList()

    val metadataFile = File(targetDirectory, "metadata.txt")
    generateMetadataFile(book, chapterFiles, metadataFile)

    // Create a list file to handle spaces in filenames
    val listFile = File(targetDirectory, "input_list.txt").apply {
      bufferedWriter().use { writer ->
        chapterFiles.forEach { file ->
          if (!file.exists()) throw IllegalArgumentException("MP3 file not found: ${file.absolutePath}")
          writer.write("file '${file.absolutePath}'\n")
        }
      }
    }

    val builder = FFmpegBuilder()
      .addExtraArgs("-f", "concat", "-safe", "0")
      .addExtraArgs("-i", listFile.absolutePath)
      .addExtraArgs("-f", "ffmetadata", "-i", metadataFile.absolutePath)
      .addInput(coverFile.absolutePath)
      .addOutput(newFile.absolutePath)
      .setAudioCodec("aac")
      .addExtraArgs("-b:a", "128k")
      .addExtraArgs("-movflags", "faststart")
      .addExtraArgs("-map", "0:a") // Audio from MP3s
      .addExtraArgs("-map_metadata", "1")
      .addExtraArgs("-map", "2:v") // Cover image
      .addExtraArgs("-disposition:v:0", "attached_pic")
      .addExtraArgs("-c:v:0", "mjpeg")
      .addExtraArgs("-id3v2_version", "3")
      .done()

    val executor = FFmpegExecutor(ffmpeg, ffprobe)
    executor.createJob(builder, object : ProgressListener {
      val durationNs = book.audiobook_info.duration * TimeUnit.SECONDS.toNanos(1)
      override fun progress(progress: Progress) {
        val percentage = progress.out_time_ns.toDouble() / durationNs.toDouble();
        lfdLogger(
          String.format(
            "[%.0f%%] status:%s time:%s ms speed:%.2fx",
            percentage * 100,
            progress.status,
            FFmpegUtils.toTimecode(progress.out_time_ns, TimeUnit.NANOSECONDS),
            progress.speed
          )
        )
      }
    }).run()

    //cleanup
    listFile.delete()
    metadataFile.delete()

    lfdLogger("M4B file created: ${newFile.absolutePath}")
  }

  private fun downloadCoverImage(coverUrl: String, outputFile: File) {
    val fullUrl = if (coverUrl.startsWith("//")) "https:$coverUrl" else coverUrl

    try {
      URI(fullUrl).toURL().openStream().use { input ->
        Files.copy(input, outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      lfdLogger("Cover image saved to ${outputFile.absolutePath}")
    } catch (e: Exception) {
      lfdLogger("Failed to download cover image: ${e.message}")
    }
  }

  private fun generateMetadataFile(
    book: Book,
    chapterFiles: List<File>,
    outputFile: File,
  ) {
    // Extract metadata
    val title = book.title
    val author = book.authors.joinToString(", ")
    val series = book.series
    val publisher = book.publisher
    val publicationDate = book.publication_date
    val genres = book.genres.map { it.name }
    val description = book.description
    val seriesNum = book.series_num


    val chapters = mutableListOf<Chapter>()
    var startTimeMs = 0L

    chapterFiles.forEachIndexed { index, file ->
      val durationMs = getMp3Duration(file)
      val endTimeMs = startTimeMs + durationMs
      chapters.add(Chapter("Chapter ${index + 1}", startTimeMs, endTimeMs))
      startTimeMs = endTimeMs
    }

    outputFile.bufferedWriter().use { writer ->
      writer.write(";FFMETADATA1\n")
      writer.write("title=\"${title}\"\n")
      writer.write("artist=\"${author}\"\n")
      writer.write("album=\"${title}${series?.let { " ($it Book ${seriesNum ?: "X"})" } ?: ""}\"\n")
      writer.write("genre=\"${genres.joinToString(", ")}\"\n")
      writer.write("date=\"${publicationDate.substring(0, 4)}\"\n")
      writer.write("publisher=\"${publisher}\"\n")
      writer.write("comment=\"${description.replace("\n", " ")}\"\n\n")

      chapters.forEach { chapter ->
        writer.write("[CHAPTER]\n")
        writer.write("TIMEBASE=1/1000\n")
        writer.write("START=${chapter.startTimeMs}\n")
        writer.write("END=${chapter.endTimeMs}\n")
        writer.write("title=\"${chapter.title}\"\n\n")
      }
    }
  }

  private fun getMp3Duration(file: File): Long {
    val probeResult = ffprobe.probe(file.absolutePath)
    val format = probeResult.format
    return TimeUnit.MILLISECONDS.convert(format.duration.toLong(), TimeUnit.SECONDS)
  }
}