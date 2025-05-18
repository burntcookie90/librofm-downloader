package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libroabs.healthchck.HealthcheckApi
import com.vishnurajeevan.libroabs.libro.FfmpegClient
import com.vishnurajeevan.libroabs.libro.LibroApiHandler
import com.vishnurajeevan.libroabs.models.ServerInfo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Semaphore
import java.io.File

@Inject
class Runner(
  private val libroApiHandler: LibroApiHandler,
  private val ffmpegClient: FfmpegClient,
  private val healthcheckApi: HealthcheckApi?,
  private val logger: LfdLogger,
  private val serverInfo: ServerInfo,
  @Named("dataDir") private val dataDir: File,
  @Named("app") private val appScope: CoroutineScope,
  @Named("processing") private val processingScope: CoroutineScope,
  private val processingSemaphore: Semaphore
) {

  init {
    if (!dataDir.exists()) {
      dataDir.mkdirs()
    }
  }
}