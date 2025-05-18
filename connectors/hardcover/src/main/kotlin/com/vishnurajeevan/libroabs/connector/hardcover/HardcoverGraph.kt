package com.vishnurajeevan.libroabs.connector.hardcover

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesGraphExtension

abstract class HardcoverScope

@ContributesGraphExtension(HardcoverScope::class)
interface HardcoverGraph {
  @ContributesGraphExtension.Factory(AppScope::class)
  interface Factory {
    fun createHardcoverGraph(): HardcoverGraph
  }
}