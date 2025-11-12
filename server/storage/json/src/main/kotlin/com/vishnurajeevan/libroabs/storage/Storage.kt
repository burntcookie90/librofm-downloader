package com.vishnurajeevan.libroabs.storage

import com.vishnurajeevan.libroabs.models.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.KSerializer
import java.io.File

interface Storage<T: Any> {
  suspend fun getData(): T

  suspend fun update(update: suspend (T) -> T)

  interface Factory<T: Any> {
    fun create(
      file: File,
      initial: T,
      serializer: KSerializer<T>,
      dispatcher: CoroutineDispatcher,
      logger: Logger,
    ): Storage<T>
  }

  interface Factory2<T: Any> {
    fun create(
      initial: T,
      file: File,
      serializer: KSerializer<T>,
    ): Storage<T>
  }

}