package com.vishnurajeevan.libroabs.storage

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
) : Storage<T> {
  private val scope = CoroutineScope(dispatcher + SupervisorJob())
  private val json = Json { prettyPrint = true }

  init {
    scope.launch {
      if (!file.exists()) {
        file.createNewFile()
        file.writeText(json.encodeToString(serializer, initial))
      }
    }
  }

  private var data: T
    get() = json.decodeFromString(serializer, file.readText())
    set(value) = file.writeText(json.encodeToString(serializer, value))

  override suspend fun getData(): T = withContext(dispatcher) { data }

  override suspend fun update(update: suspend (T) -> T) {
    scope.launch {
      data = update(getData())
    }
  }

  class Factory<T : Any> : Storage.Factory<T> {
    override fun create(
      file: File,
      initial: T,
      serializer: KSerializer<T>,
      dispatcher: CoroutineDispatcher
    ): Storage<T> =
      RealStorage(
        file = file,
        initial = initial,
        serializer = serializer,
        dispatcher = dispatcher
      )
  }
}