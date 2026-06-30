package com.vishnurajeevan.libroabs.graph

import com.vishnurajeevan.libroabs.App
import com.vishnurajeevan.libroabs.StorageMigrator
import com.vishnurajeevan.libroabs.libro.LibroApiHandler
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.createPath
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import net.bramp.ffmpeg.FFmpeg
import net.bramp.ffmpeg.FFmpegExecutor
import net.bramp.ffmpeg.FFprobe
import java.io.File

@DependencyGraph(scope = AppScope::class)
interface AppComponent {
  val serverInfo: ServerInfo

  val app: App

  val logger: Logger

  val storageMigrator: StorageMigrator

  val libroClient: LibroApiHandler

  @Named("healthcheck-host")
  @SingleIn(AppScope::class)
  @Provides
  fun hcioHost(): String = serverInfo.healthCheckHost

  @Named("healthcheck-id")
  @SingleIn(AppScope::class)
  @Provides
  fun hcioToken(): String? = serverInfo.healthCheckId

  @Named("hardcover-token")
  @SingleIn(AppScope::class)
  @Provides
  fun hcToken(): String? = serverInfo.trackerToken

  @Named("hardcover-endpoint")
  @SingleIn(AppScope::class)
  @Provides
  fun hcEndpoint(): String = serverInfo.trackerEndpoint

  @Named("ffprobePath")
  @Provides
  fun ffprobePath(): String = serverInfo.ffprobePath

  @Provides
  fun appLogLevel(): ApplicationLogLevel = serverInfo.logLevel

  @Provides
  @SingleIn(AppScope::class)
  fun ffmpegExecutor(): FFmpegExecutor = FFmpegExecutor(
    FFmpeg(serverInfo.ffmpegPath),
    FFprobe(serverInfo.ffprobePath)
  )

  @Provides
  @Named("parallel")
  fun parallelCount(): Int = serverInfo.parallelCount

  @Provides
  @SingleIn(AppScope::class)
  fun providesTargetDir(lfdLogger: Logger): (Book) -> File = { book ->
    File("${serverInfo.mediaDir}/${book.createPath(serverInfo.pathPattern)}")
      .also {
        lfdLogger.v("Target Directory: $it")
      }
  }

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides serverInfo: ServerInfo): AppComponent
  }
}