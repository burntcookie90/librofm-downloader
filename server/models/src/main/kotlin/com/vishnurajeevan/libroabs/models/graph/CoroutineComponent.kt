package com.vishnurajeevan.libroabs.models.graph

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import me.tatarka.inject.annotations.Provides
import me.tatarka.inject.annotations.Qualifier
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@ContributesTo(AppScope::class)
interface CoroutineComponent {
  @Provides
  @Io
  fun ioDispatcher() = Dispatchers.IO

  @Provides
  @App
  fun appDispatcher() = Dispatchers.Default

  @Provides
  @App
  @SingleIn(AppScope::class)
  fun appScope(@App coroutineDispatcher: CoroutineDispatcher) = CoroutineScope(coroutineDispatcher + SupervisorJob())

  @Provides
  @Io
  @SingleIn(AppScope::class)
  fun processingScope(@Io coroutineDispatcher: CoroutineDispatcher) =
    CoroutineScope(coroutineDispatcher + SupervisorJob())

  @Provides
  @SingleIn(AppScope::class)
  fun processingSemaphore(@Named("parallel") count: Int) = Semaphore(count)
}

@Qualifier
annotation class Io

@Qualifier
annotation class App