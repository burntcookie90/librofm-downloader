package com.vishnurajeevan.libroabs.graph

import com.vishnurajeevan.libroabs.App
import com.vishnurajeevan.libroabs.StorageMigrator
import com.vishnurajeevan.libroabs.libro.LibroApiHandler
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.createPath
import me.tatarka.inject.annotations.Provides
import net.bramp.ffmpeg.FFmpeg
import net.bramp.ffmpeg.FFmpegExecutor
import net.bramp.ffmpeg.FFprobe
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn
import java.io.File

@MergeComponent(AppScope::class)
@SingleIn(AppScope::class)
abstract class AppComponent(
  @get:Provides val serverInfo: ServerInfo
) {
  abstract val app: App

  abstract val logger: Logger

  abstract val storageMigrator: StorageMigrator

  abstract val libroClient: LibroApiHandler

  @Named("healthcheck-host")
  @SingleIn(AppScope::class)
  @Provides
  fun hcioHost(): String = serverInfo.healthCheckHost

  @Named("healthcheck-id")
  @SingleIn(AppScope::class)
  @Provides
  fun hcioToken() = serverInfo.healthCheckId

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
  fun ffprobePath() = serverInfo.ffprobePath

  @Provides
  fun appLogLevel() = serverInfo.logLevel

  @Provides
  @SingleIn(AppScope::class)
  fun ffmpegExecutor() = FFmpegExecutor(
    FFmpeg(serverInfo.ffmpegPath),
    FFprobe(serverInfo.ffprobePath)
  )

  @Provides
  @Named("parallel")
  fun parallelCount() = serverInfo.parallelCount

  @Provides
  @SingleIn(AppScope::class)
  fun providesTargetDir(lfdLogger: Logger): (Book) -> File = { book ->
    File("${serverInfo.mediaDir}/${book.createPath(serverInfo.pathPattern)}")
      .also {
        lfdLogger.v("Target Directory: $it")
      }
  }

}