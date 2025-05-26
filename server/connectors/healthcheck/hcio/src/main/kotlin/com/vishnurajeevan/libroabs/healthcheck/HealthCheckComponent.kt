package com.vishnurajeevan.libroabs.healthcheck

import com.vishnurajeevan.libroabs.models.graph.Named
import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.HttpClient
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(AppScope::class)
interface HealthCheckComponent {

  @Provides
  fun hcioApi(
    httpClient: HttpClient,
    @Named("healthcheck-host") healthCheckHost: String
  ): HealthcheckApi =
    Ktorfit.Builder()
      .baseUrl(if (healthCheckHost.endsWith("/")) healthCheckHost else "${healthCheckHost}/")
      .httpClient(httpClient)
      .build()
      .createHealthcheckApi()
}