package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps to Hardcover's limit of 60 requests a minute by spacing requests [minIntervalMillis] apart, and waits out a
 * 429 when Hardcover says how long to wait. A wait longer than [maxWaitMillis] (the daily limit, say) is not waited
 * out: the 429 is returned so the sync can stop.
 */
internal class RateLimitInterceptor(
  private val minIntervalMillis: Long = 1_000,
  private val maxRetries: Int = 3,
  private val maxWaitMillis: Long = 120_000,
  private val now: () -> Long = System::currentTimeMillis,
  private val sleep: suspend (Long) -> Unit = { delay(it) },
) : HttpInterceptor {
  private val mutex = Mutex()
  private var nextSlot = 0L

  override suspend fun intercept(request: HttpRequest, chain: HttpInterceptorChain): HttpResponse {
    var retries = 0
    while (true) {
      awaitSlot()
      val response = chain.proceed(request)
      if (response.statusCode != 429 || retries >= maxRetries) return response
      val wait = retryAfterMillis(response) ?: (10_000L shl retries)
      if (wait > maxWaitMillis) return response
      response.body?.close()
      sleep(wait)
      retries++
    }
  }

  private suspend fun awaitSlot() {
    val wait = mutex.withLock {
      val now = now()
      val slot = maxOf(now, nextSlot)
      nextSlot = slot + minIntervalMillis
      slot - now
    }
    if (wait > 0) sleep(wait)
  }

  /** `Retry-After` in seconds, or else the `X-RateLimit-Reset` time (epoch seconds) of the per-minute limit. */
  private fun retryAfterMillis(response: HttpResponse): Long? {
    fun header(name: String) = response.headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value?.trim()
    header("Retry-After")?.toLongOrNull()?.let { return it * 1_000 }
    header("X-RateLimit-Reset")?.toLongOrNull()?.let { return (it * 1_000 - now()).coerceAtLeast(0) }
    return null
  }
}
