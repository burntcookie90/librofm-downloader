package com.vishnurajeevan.libroabs.healthcheck

import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Path

interface HealthcheckApi {
  @POST("{id}")
  suspend fun ping(@Path("id") id: String)

  @POST("{id}/start")
  suspend fun start(@Path("id") id: String)
}