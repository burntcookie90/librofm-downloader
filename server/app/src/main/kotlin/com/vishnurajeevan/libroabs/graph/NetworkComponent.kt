package com.vishnurajeevan.libroabs.graph

import com.vishnurajeevan.libroabs.libro.LibroAPI
import com.vishnurajeevan.libroabs.libro.createLibroAPI
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import de.jensklingenberg.ktorfit.Ktorfit
import de.jensklingenberg.ktorfit.converter.ResponseConverterFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

@ContributesTo(AppScope::class)
interface NetworkComponent {
  @Provides
  fun defaultClient(
    serverInfo: ServerInfo,
    _logger: com.vishnurajeevan.libroabs.models.Logger
  ): HttpClient = HttpClient {
    install(Logging) {
      logger = object : Logger {
        override fun log(message: String) {
          _logger.v(message)
        }

      }
      level = when (serverInfo.logLevel) {
        ApplicationLogLevel.NONE -> LogLevel.NONE
        ApplicationLogLevel.INFO -> LogLevel.INFO
        ApplicationLogLevel.VERBOSE -> LogLevel.HEADERS
      }
    }
  }

  @Provides
  @Named("download")
  fun downloadClient(client: HttpClient): HttpClient = client.config {
    install(HttpTimeout) {
      requestTimeoutMillis = 5 * 60 * 1000
    }
  }

  @Provides
  fun libroApi(client: HttpClient, serverInfo: ServerInfo): LibroAPI = Ktorfit.Builder()
    .baseUrl("https://libro.fm/")
    .httpClient(client.config {
      defaultRequest {
        contentType(ContentType.Application.Json)
        serverInfo.libroFmHeaders.forEach { key, value ->
          header(key, value)
        }
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