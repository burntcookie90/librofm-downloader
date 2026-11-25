package com.vishnurajeevan.libroabs.storage

import com.vishnurajeevan.libroabs.models.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON-file storage. In a dry run the file is read if it exists but never written: changes are kept in memory for
 * the life of the process.
 */
@OptIn(ExperimentalSerializationApi::class)
class RealStorage<T : Any>(
  initial: T,
  private val file: File,
  private val serializer: KSerializer<T>,
  private val dispatcher: CoroutineDispatcher,
  private val logger: Logger,
  private val dryRun: Boolean = false,
) : Storage<T> {
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
  }

  private val mutex = Mutex()

  /** The dry run's copy of the data, starting from the file when there is one. */
  private var dryRunData: T? = null

  init {
    if (dryRun) {
      logger.v("Dry Run: keeping storage ${file.path} in memory")
      dryRunData = if (file.exists()) read() else initial
    } else if (!file.exists()) {
      logger.v("Creating storage for ${file.path}")
      write(initial)
    }
  }

  override suspend fun getData(): T = withContext(dispatcher) { mutex.withLock { current() } }

  /**
   * Applies [update] and writes the result before returning, so a following [getData] sees it. If [update] throws,
   * the stored value is left unchanged and the exception reaches the caller.
   */
  override suspend fun update(update: suspend (T) -> T) = withContext(dispatcher) {
    mutex.withLock {
      val new = update(current())
      if (dryRun) {
        logger.v("Dry Run: not writing storage ${file.path}")
        dryRunData = new
      } else {
        logger.v("Writing storage ${file.path}")
        write(new)
      }
    }
  }

  private fun current(): T = if (dryRun) dryRunData!! else read()

  private fun read(): T = json.decodeFromString(serializer, file.readText())

  /** Writes via a temporary file so a crash mid-write can't leave truncated JSON behind. */
  private fun write(value: T) {
    val tmp = File(file.absoluteFile.parentFile, "${file.name}.tmp")
    tmp.writeText(json.encodeToString(serializer, value))
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  class Factory<T : Any> : Storage.Factory<T> {
    override fun create(
      file: File,
      initial: T,
      serializer: KSerializer<T>,
      dispatcher: CoroutineDispatcher,
      logger: Logger,
      dryRun: Boolean,
    ): Storage<T> =
      RealStorage(
        file = file,
        initial = initial,
        serializer = serializer,
        dispatcher = dispatcher,
        logger = logger,
        dryRun = dryRun,
      )
  }
}
