package com.vishnurajeevan.libroabs.healthcheck

import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.HttpClient

fun healthcheckApi(
  httpClient: HttpClient,
  healthCheckHost: String,
): HealthcheckApi = Ktorfit.Builder()
  .baseUrl(if (healthCheckHost.endsWith("/")) healthCheckHost else "${healthCheckHost}/")
  .httpClient(httpClient)
  .build()
  .createHealthcheckApi()