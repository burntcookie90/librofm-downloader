package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Paces outbound requests and honours `Retry-After` on 429.
 *
 * A first sync of a large library issues one mutation per book back to back, as fast as round trips
 * allow, with nothing on the client side bounding the rate. Hardcover publishes a per-minute request
 * limit; this keeps us under it by construction rather than by luck, and backs off rather than
 * hammering when we are told to slow down.
 */
internal class RateLimitInterceptor(
  private val minInterval: Duration,
  private val maxRetryAfter: Duration = 60.seconds,
  private val logger: (String) -> Unit = {},
) : HttpInterceptor {

  private val mutex = Mutex()
  private var nextAllowedAt = 0L

  override suspend fun intercept(request: HttpRequest, chain: HttpInterceptorChain): HttpResponse {
    awaitTurn()
    val response = chain.proceed(request)
    if (response.statusCode != 429) return response

    val retryAfter = response.retryAfter() ?: minInterval
    logger("Hardcover rate limited us, waiting ${retryAfter} before retrying")
    delay(retryAfter.coerceAtMost(maxRetryAfter))
    awaitTurn()
    return chain.proceed(request)
  }

  /** Spaces request starts by [minInterval]. The lock is held only while reserving a slot. */
  private suspend fun awaitTurn() {
    val waitFor = mutex.withLock {
      val now = System.currentTimeMillis()
      val startAt = maxOf(now, nextAllowedAt)
      nextAllowedAt = startAt + minInterval.inWholeMilliseconds
      startAt - now
    }
    if (waitFor > 0) delay(waitFor)
  }

  private fun HttpResponse.retryAfter(): Duration? =
    headers.firstOrNull { it.name.equals("Retry-After", ignoreCase = true) }
      ?.value
      ?.trim()
      ?.toLongOrNull()
      ?.seconds
}
