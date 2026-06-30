package com.vishnurajeevan.libroabs.models.graph

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore

@ContributesTo(AppScope::class)
interface CoroutineComponent {
  @Provides
  @Io
  fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

  @Provides
  @App
  fun appDispatcher(): CoroutineDispatcher = Dispatchers.Default

  @Provides
  @App
  @SingleIn(AppScope::class)
  fun appScope(@App coroutineDispatcher: CoroutineDispatcher): CoroutineScope = CoroutineScope(coroutineDispatcher + SupervisorJob())

  @Provides
  @Io
  @SingleIn(AppScope::class)
  fun processingScope(@Io coroutineDispatcher: CoroutineDispatcher): CoroutineScope =
    CoroutineScope(coroutineDispatcher + SupervisorJob())

  @Provides
  @SingleIn(AppScope::class)
  fun processingSemaphore(@Named("parallel") count: Int): Semaphore = Semaphore(count)
}

@Qualifier
annotation class Io

@Qualifier
annotation class App