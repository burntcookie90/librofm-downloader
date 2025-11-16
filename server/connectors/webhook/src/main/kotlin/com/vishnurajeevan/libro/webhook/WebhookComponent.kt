package com.vishnurajeevan.libro.webhook

import de.jensklingenberg.ktorfit.Ktorfit
import io.ktor.client.HttpClient
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(AppScope::class)
interface WebhookComponent {
  @Provides
  fun webhookApi(
    httpClient: HttpClient,
  ) = Ktorfit.Builder()
    .httpClient(httpClient)
    .build()
    .createWebhookApi()
}