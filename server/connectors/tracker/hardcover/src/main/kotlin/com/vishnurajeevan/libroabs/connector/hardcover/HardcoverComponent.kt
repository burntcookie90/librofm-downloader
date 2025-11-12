package com.vishnurajeevan.libroabs.connector.hardcover

import com.vishnurajeevan.libroabs.connector.TrackerConnector
import com.vishnurajeevan.libroabs.models.graph.Named
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

@ContributesTo(AppScope::class)
interface HardcoverComponent {

  @Provides
  @SingleIn(AppScope::class)
  fun hardcoverTracker(
    @Named("hardcover-token") token: String?,
    @Named("hardcover-endpoint") endpoint: String,
    factory: (String, String) -> TrackerConnector
  ) : TrackerConnector? {
    return token?.let {
      if (it.isNotEmpty()) {
        factory(it, endpoint)
      }
      else {
        null
      }
    }
  }
}