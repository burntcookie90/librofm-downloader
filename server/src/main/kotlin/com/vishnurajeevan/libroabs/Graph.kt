package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libroabs.healthchck.HealthcheckApi
import com.vishnurajeevan.libroabs.healthchck.createHealthcheckApi
import com.vishnurajeevan.libroabs.libro.FfmpegClient
import com.vishnurajeevan.libroabs.libro.LibroAPI
import com.vishnurajeevan.libroabs.libro.createLibroAPI
import com.vishnurajeevan.libroabs.models.ServerInfo
import de.jensklingenberg.ktorfit.Ktorfit
import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import net.bramp.ffmpeg.FFmpeg
import net.bramp.ffmpeg.FFmpegExecutor
import net.bramp.ffmpeg.FFprobe
import java.io.File

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class, isExtendable = true)
interface Graph {

  val serverInfo: ServerInfo
  val runner: Runner

  @Provides
  @SingleIn(AppScope::class)
  private fun logger(serverInfo: ServerInfo): LfdLogger = LfdLogger { message ->
    when (serverInfo.logLevel) {
      ApplicationLogLevel.INFO, ApplicationLogLevel.VERBOSE -> println(message)
      else -> {}
    }
  }

  @Provides
  @SingleIn(AppScope::class)
  private fun defaultHttpClient(
    lfdLogger: LfdLogger,
    serverInfo: ServerInfo,
  ): HttpClient = HttpClient {
    install(Logging) {
      logger = object : Logger {
        override fun log(message: String) {
          lfdLogger.log(message)
        }

      }
      level = when (serverInfo.logLevel) {
        ApplicationLogLevel.NONE -> LogLevel.NONE
        ApplicationLogLevel.INFO -> LogLevel.INFO
        ApplicationLogLevel.VERBOSE -> LogLevel.ALL
      }
    }
  }

  @Named("dataDir")
  @Provides
  @SingleIn(AppScope::class)
  fun dataDir(serverInfo: ServerInfo): File = File(serverInfo.dataDir)

  @Named("dryRun")
  @Provides
  @SingleIn(AppScope::class)
  fun dryRun(serverInfo: ServerInfo): Boolean = serverInfo.dryRun

  @Named("hardcover-token")
  @Provides
  @SingleIn(AppScope::class)
  fun hardcoverToken(serverInfo: ServerInfo): String? = serverInfo.hardcoverToken

  @Provides
  @SingleIn(AppScope::class)
  private fun libroApi(client: HttpClient): LibroAPI = Ktorfit.Builder()
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
    .createLibroAPI()

  @Provides
  @SingleIn(AppScope::class)
  fun ffmpegExecutor(
    serverInfo: ServerInfo
  ): FFmpegExecutor = FFmpegExecutor(
    FFmpeg(serverInfo.ffmpegPath),
    FFprobe(serverInfo.ffprobePath)
  )

  @Provides
  @SingleIn(AppScope::class)
  fun ffmpegClient(
    serverInfo: ServerInfo,
    ffmpegExecutor: FFmpegExecutor,
    lfdLogger: LfdLogger,
  ): FfmpegClient = FfmpegClient(
    serverInfo.ffprobePath,
    ffmpegExecutor,
    lfdLogger::log
  )

  @Provides
  @SingleIn(AppScope::class)
  fun healthCheckClientApi(
    serverInfo: ServerInfo,
    client: HttpClient
  ): HealthcheckApi? = if (!serverInfo.healthCheckId.isNullOrEmpty()
    && !serverInfo.healthCheckHost.isNullOrEmpty()) {
    serverInfo.healthCheckHost.let {
      Ktorfit.Builder()
        .baseUrl(if (it.endsWith("/")) it else "$it/")
        .httpClient(client)
        .build()
        .createHealthcheckApi()
    }
  } else {
    null
  }

  @Named("app")
  @Provides
  @SingleIn(AppScope::class)
  fun appScope(): CoroutineScope = CoroutineScope(Dispatchers.Default)

  @Named("processing")
  @Provides
  @SingleIn(AppScope::class)
  fun processingScope(): CoroutineScope =  CoroutineScope(Dispatchers.IO + SupervisorJob())

  @Named("io-dispatcher")
  @Provides
  fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

  @Provides
  @SingleIn(AppScope::class)
  fun processingSemaphore(serverInfo: ServerInfo): Semaphore = Semaphore(serverInfo.parallelCount)

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides serverInfo: ServerInfo): Graph
  }
}

fun interface LfdLogger {
  fun log(message: String)
}