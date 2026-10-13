package com.vishnurajeevan.libroabs.libro

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.IOException

/** libro.fm answered 401: the saved token (or, when logging in, the credentials) was rejected. */
class LibroUnauthorizedException : IOException("libro.fm rejected the request as unauthorized")

/**
 * HTTP client setup for the libro.fm API. Reads that hit a dropped connection, a timeout, rate limiting or a server
 * error are retried with backoff; [retryDelayMs] is the first delay.
 */
fun HttpClientConfig<*>.configureLibroApi(headers: Map<String, String>, retryDelayMs: Long = 1_000) {
  install(HttpTimeout) {
    connectTimeoutMillis = 30_000
    requestTimeoutMillis = 60_000
  }
  install(HttpRequestRetry) {
    maxRetries = 3
    // Only reads: a repeated login or wishlist add is not worth the surprise.
    retryIf { request, response ->
      request.method == HttpMethod.Get &&
        (response.status == HttpStatusCode.TooManyRequests || response.status.value >= 500)
    }
    retryOnExceptionIf { request, cause ->
      request.method == HttpMethod.Get && cause is IOException && cause !is LibroUnauthorizedException
    }
    exponentialDelay(baseDelayMs = retryDelayMs, maxDelayMs = 30_000, randomizationMs = retryDelayMs)
  }
  defaultRequest {
    contentType(ContentType.Application.Json)
    headers.forEach { (key, value) -> header(key, value) }
  }
  // libro.fm reports a rejected token as 401 with `{"error": "Not authorized."}`, which would otherwise deserialize
  // into an empty response (an empty library, say).
  HttpResponseValidator {
    validateResponse { if (it.status == HttpStatusCode.Unauthorized) throw LibroUnauthorizedException() }
  }
  install(ContentNegotiation) {
    json(Json {
      isLenient = true
      ignoreUnknownKeys = true
    })
  }
}
