package com.vishnurajeevan.libroabs.graph

import com.vishnurajeevan.libroabs.libro.LibroAPI
import com.vishnurajeevan.libroabs.libro.createLibroAPI
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import de.jensklingenberg.ktorfit.Ktorfit
import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo

@ContributesTo(AppScope::class)
interface NetworkComponent {
  @Provides
  fun defaultClient(
    serverInfo: ServerInfo,
    _logger: com.vishnurajeevan.libroabs.models.Logger
  ) = HttpClient {
    install(Logging) {
      logger = object : Logger {
        override fun log(message: String) {
          _logger.log(message)
        }

      }
      level = when (serverInfo.logLevel) {
        ApplicationLogLevel.NONE -> LogLevel.NONE
        ApplicationLogLevel.INFO -> LogLevel.INFO
        ApplicationLogLevel.VERBOSE -> LogLevel.ALL
      }
    }
  }

  @Provides
  @Named("download")
  fun downloadClient(client: HttpClient) = client.config {
    install(HttpTimeout) {
      requestTimeoutMillis = 5 * 60 * 1000
    }
  }

  @Provides
  fun libroApi(client: HttpClient): LibroAPI = Ktorfit.Builder()
    .baseUrl("https://libro.fm/")
    .httpClient(client.config {
      defaultRequest {
        contentType(ContentType.Application.Json)
      }
      install(ContentNegotiation) {
        json(Json {
          isLenient = true
          ignoreUnknownKeys = true
        })
      }
    })
    .converterFactories(ResponseConverterFactory())
    .build()
    .createLibroAPI()
}