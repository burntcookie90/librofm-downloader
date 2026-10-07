package com.vishnurajeevan.libroabs.healthcheck

import de.jensklingenberg.ktorfit.Ktorfit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides
import io.ktor.client.*

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