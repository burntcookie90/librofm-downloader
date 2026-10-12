package com.vishnurajeevan.libroabs.libro

import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpResponseValidator
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.io.IOException

/** libro.fm answered 401: the saved token (or, when logging in, the credentials) was rejected. */
class LibroUnauthorizedException : IOException("libro.fm rejected the request as unauthorized")

/** HTTP client setup for the libro.fm API. */
fun HttpClientConfig<*>.configureLibroApi(headers: Map<String, String>) {
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
