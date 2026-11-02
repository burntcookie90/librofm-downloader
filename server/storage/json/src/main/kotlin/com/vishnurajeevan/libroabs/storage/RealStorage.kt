package com.vishnurajeevan.libroabs.storage

import com.vishnurajeevan.libroabs.models.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
  private val dryRun: Boolean
) : Storage<T> {
  private val scope = CoroutineScope(dispatcher + SupervisorJob())
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
  }

  private val writeQueue: MutableStateFlow<T?> = MutableStateFlow(null)

  private lateinit var _dryRunData: T

  private var data: T
    get() {
      return if (dryRun) _dryRunData
      else json.decodeFromString(serializer, file.readText())
    }
    set(value) {
      if (dryRun) {
        _dryRunData = value
      } else {
        file.writeText(json.encodeToString(serializer, value))
      }
    }

  private val mutex = Mutex()


  init {
    runBlocking {
      if (dryRun) {
        logger.v("Dry Run: Initializing storage ${file.path}")
        _dryRunData = initial
      }
      else if (!file.exists()) {
        logger.v("Creating storage for ${file.path}")
        withContext(Dispatchers.IO) {
          file.createNewFile()
          file.writeText(json.encodeToString(serializer, initial))
        }
      }
    }

    scope.launch {
      writeQueue.filterNotNull().collect {
        mutex.withLock {
          logger.v("Writing $it to storage")
          data = it
        }
      }
    }
  }

  override suspend fun getData(): T = withContext(dispatcher) { data }

  override suspend fun update(update: suspend (T) -> T) {
    scope.launch {
      val new = update(data)
      logger.v("dispatching $new to storage ${file.path}")
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
      dryRun: Boolean
    ): Storage<T> =
      RealStorage(
        file = file,
        initial = initial,
        serializer = serializer,
        dispatcher = dispatcher,
        logger = logger,
        dryRun = dryRun
      )
  }
}