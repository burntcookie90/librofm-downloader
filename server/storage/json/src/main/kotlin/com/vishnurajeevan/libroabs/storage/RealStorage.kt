package com.vishnurajeevan.libroabs.storage

import com.vishnurajeevan.libroabs.models.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

@OptIn(ExperimentalSerializationApi::class)
class RealStorage<T : Any>(
  initial: T,
  private val file: File,
  private val serializer: KSerializer<T>,
  private val dispatcher: CoroutineDispatcher,
  private val logger: Logger,
) : Storage<T> {
  private val scope = CoroutineScope(dispatcher + SupervisorJob())
  private val json = Json { prettyPrint = true }

  private val writeQueue: MutableStateFlow<T?> = MutableStateFlow(null)
  private var data: T
    get() = json.decodeFromString(serializer, file.readText())
    set(value) = file.writeText(json.encodeToString(serializer, value))

  private val mutex = Mutex()


  init {
    runBlocking {
      if (!file.exists()) {
        logger.log("Creating storage for ${file.path}")
        file.createNewFile()
        file.writeText(json.encodeToString(serializer, initial))
      }
    }

    scope.launch {
      writeQueue.filterNotNull().collect {
        mutex.withLock {
          logger.log("Writing $it to storage")
          data = it
        }
      }
    }
  }

  override suspend fun getData(): T = withContext(dispatcher) { data }

  override suspend fun update(update: suspend (T) -> T) {
    scope.launch {
      val new = update(data)
      logger.log("dispatching $new to storage ${file.path}")
      writeQueue.emit(new)
    }
  }

  class Factory<T : Any> : Storage.Factory<T> {
    override fun create(
      file: File,
      initial: T,
      serializer: KSerializer<T>,
      dispatcher: CoroutineDispatcher,
      logger: Logger,
    ): Storage<T> =
      RealStorage(
        file = file,
        initial = initial,
        serializer = serializer,
        dispatcher = dispatcher,
        logger = logger
      )
  }
}