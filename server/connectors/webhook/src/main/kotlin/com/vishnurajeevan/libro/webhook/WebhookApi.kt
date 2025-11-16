package com.vishnurajeevan.libro.webhook

import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.Url

interface WebhookApi {
  @POST
  suspend fun postToWebhook(@Url url: String)
}