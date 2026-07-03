package com.vishnurajeevan.libroabs.connector.hardcover

import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.models.graph.Named
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

@ContributesTo(AppScope::class)
interface HardcoverComponent {

  @Provides
  @SingleIn(AppScope::class)
  fun hardcoverTracker(
    @Named("hardcover-token") token: String?,
    @Named("hardcover-endpoint") endpoint: String,
    factory: HardcoverTrackerConnector.Factory
  ) : TrackerConnector? {
    return token?.let {
      if (it.isNotEmpty()) {
        factory.create(it, endpoint)
      }
      else {
        null
      }
    }
  }
}