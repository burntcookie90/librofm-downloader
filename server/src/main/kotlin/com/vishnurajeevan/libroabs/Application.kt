package com.vishnurajeevan.libroabs

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.NoOpCliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.vishnurajeevan.libroabs.libro.Book
import com.vishnurajeevan.libroabs.libro.LibraryMetadata
import com.vishnurajeevan.libroabs.libro.LibroApiHandler
import com.vishnurajeevan.libroabs.libro.M4BUtil
import io.github.kevincianfarini.cardiologist.intervalPulse
import io.ktor.client.HttpClient
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

fun main(args: Array<String>) {
    NoOpCliktCommand(name = "librofm-abs")
        .subcommands(Run())
        .main(args)
}

class Run : CliktCommand("run") {
    private val port by option("--port")
        .int()
        .default(8080)

    private val dataDir by option("--data-dir")
        .default("/data")

    private val mediaDir by option("--media-dir")
        .default("/media")

    private val syncInterval by option("--sync-interval", envvar = "SYNC_INTERVAL")
        .choice("h", "d", "w")
        .default("d")

    private val dryRun by option("--dry-run", "-n", envvar = "DRY_RUN")
        .flag(default = false)

    private val renameChapters by option("--rename-chapters", envvar = "RENAME_CHAPTERS")
        .flag(default = false)

    private val writeTitleTag by option("--write-title-tag", envvar = "WRITE_TITLE_TAG")
        .flag(default = false)

    private val convertToM4b by option("--convert-to-m4b", envvar = "CONVERT_TO_M4B")
        .flag(default = false)

    private val ffmpegPath by option("--ffmpeg-path")
        .default("/usr/bin/ffmpeg")

    private val ffprobePath by option("--ffprobe-path")
        .default("/usr/bin/ffprobe")

    private val verbose by option("--verbose", "-v", envvar = "VERBOSE")
        .flag(default = false)

    // Limits the number of books pulled down to 1
    private val devMode by option("--dev-mode", "-d", envvar = "DEV_MODE")
        .flag(default = false)

    private val libroFmUsername by option("--libro-fm-username", envvar = "LIBRO_FM_USERNAME")
        .required()

    private val libroFmPassword by option("--libro-fm-password", envvar = "LIBRO_FM_PASSWORD")
        .required()

    private val lfdLogger: (String) -> Unit = {
        if (verbose) {
            println(it)
        }
    }

    private val libroFmApi by lazy {
        LibroApiHandler(
            client = HttpClient { },
            dataDir = dataDir,
            dryRun = dryRun,
            verbose = verbose,
            lfdLogger = lfdLogger
        )
    }

    private val m4bUtil by lazy {
        M4BUtil(ffmpegPath, ffprobePath)
    }

    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun run() {
        println(
            """
        Starting up!
        internal port: $port
        syncInterval: $syncInterval
        dryRun: $dryRun
        renameChapters: $renameChapters
        writeTitleTag: $writeTitleTag
        convertToM4b: $convertToM4b
        ffmpegPath: $ffmpegPath
        ffprobePath: $ffprobePath
        verbose: $verbose
        libroFmUsername: $libroFmUsername
        libroFmPassword: ${libroFmPassword.map { "*" }.joinToString("")}
      """.trimIndent()
        )

        runBlocking {
            val dataDir = File(dataDir).apply {
                if (!exists()) {
                    mkdirs()
                }
            }
            val tokenFile = File("$dataDir/token.txt")
            if (!tokenFile.exists()) {
                lfdLogger("Token file not found, logging in")
                libroFmApi.fetchLoginData(libroFmUsername, libroFmPassword)
            }

            libroFmApi.fetchLibrary()
            processLibrary()

            launch {
                lfdLogger("Sync Interval: $syncInterval")
                val syncIntervalTimeUnit = when (syncInterval) {
                    "h" -> 1.hours
                    "d" -> 1.days
                    "w" -> 7.days
                    else -> error("Unhandled sync interval")
                }

                Clock.System.intervalPulse(syncIntervalTimeUnit)
                    .beat { scheduled, occurred ->
                        lfdLogger("Checking library on pulse!")
                        libroFmApi.fetchLibrary()
                        processLibrary()
                    }
            }

            serverScope.launch {
                embeddedServer(
                    factory = Netty,
                    port = port,
                    host = "0.0.0.0",
                    module = {
                        routing {
                            post("/update") {
                                call.respondText("Updating!")
                                libroFmApi.fetchLibrary()
                                processLibrary()
                            }
                            post("/convertToM4b/{isbn}") {
                                call.respondText("Starting process!")
                                call.parameters["isbn"]?.let { isbn ->
                                    if (isbn == "all") {
                                        convertAllBooksToM4b()
                                    } else {
                                        convertBookToM4b(isbn)
                                    }
                                }
                            }
                        }
                    }
                ).start(wait = true)
            }
        }
    }

    private fun getLibrary(): LibraryMetadata {
        return Json.decodeFromString<LibraryMetadata>(
            File("$dataDir/library.json").readText()
        )
    }

    private suspend fun processLibrary() {
        val localLibrary = getLibrary()

        localLibrary.audiobooks
            .let {
                if (devMode) {
                    it.take(1)
                } else {
                    it
                }
            }
            .forEach { book ->
                val targetDir = File("$mediaDir/${book.authors.first()}/${book.title}")

                if (!targetDir.exists()) {
                    lfdLogger("downloading ${book.title}")
                    targetDir.mkdirs()
                    val downloadData = libroFmApi.fetchDownloadMetadata(book.isbn)
                    libroFmApi.fetchAudioBook(
                        data = downloadData.parts,
                        targetDirectory = targetDir
                    )

                    if (renameChapters) {
                        libroFmApi.renameChapters(
                            title = book.title,
                            tracks = downloadData.tracks,
                            targetDirectory = targetDir,
                            writeTitleTag = writeTitleTag
                        )
                    }
                    if (convertToM4b) {
                        lfdLogger("Converting ${book.title} from mp3 to m4b.")
                        m4bUtil.convertBookToM4b(book, targetDir)
                    }
                } else {
                    lfdLogger("skipping ${book.title} as it exists on the filesystem!")
                }
            }
    }

    private suspend fun convertAllBooksToM4b() {
        val localLibrary = getLibrary()

        localLibrary.audiobooks
            .let {
                if (devMode) {
                    it.take(1)
                } else {
                    it
                }
            }
            .forEach { book ->
                convertBookToM4b(book)
            }
    }

    private suspend fun convertBookToM4b(isbn: String) {
        val localLibrary = getLibrary()
        val book = localLibrary.audiobooks.find { it.isbn == isbn }
        if (book == null) {
            lfdLogger("Book with isbn $isbn not found!")
        } else {
            convertBookToM4b(book)
        }
    }

    private suspend fun convertBookToM4b(book: Book) {
        val targetDir = File("$mediaDir/${book.authors.first()}/${book.title}")
        if (!targetDir.exists()) {
            lfdLogger("Book ${book.title} is not downloaded yet!")
            return
        }
        val targetFile = File("$mediaDir/${book.authors.first()}/${book.title}/${book.title}.m4b")
        if (targetFile.exists()) {
            lfdLogger("Skipping ${book.title} as it's already converted!")
            return
        }
        lfdLogger("Converting ${book.title} from mp3 to m4b.")
        m4bUtil.convertBookToM4b(book, targetDir)
    }
}

