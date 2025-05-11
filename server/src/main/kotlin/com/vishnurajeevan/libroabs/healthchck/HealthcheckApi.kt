package com.vishnurajeevan.libroabs.healthchck

import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Path
import de.jensklingenberg.ktorfit.http.Url

interface HealthcheckApi {
  @POST("{id}")
  suspend fun ping(@Path("id") id: String)
}