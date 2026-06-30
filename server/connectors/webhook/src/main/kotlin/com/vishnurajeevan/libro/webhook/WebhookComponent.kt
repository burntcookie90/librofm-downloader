package com.vishnurajeevan.libro.webhook

import de.jensklingenberg.ktorfit.Ktorfit
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import io.ktor.client.*

@ContributesTo(AppScope::class)
interface WebhookComponent {
  @Provides
  fun webhookApi(
    httpClient: HttpClient,
  ): WebhookApi = Ktorfit.Builder()
    .httpClient(httpClient)
    .build()
    .createWebhookApi()
}