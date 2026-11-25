package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.apollo.api.http.HttpMethod
import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptorChain
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RateLimitInterceptorTest {
  private var clock = 0L
  private val sleeps = mutableListOf<Long>()
  private val request = HttpRequest.Builder(HttpMethod.Post, "https://hardcover.test/graphql").build()

  private fun limiter(maxRetries: Int = 3) = RateLimitInterceptor(
    minIntervalMillis = 1_000,
    maxRetries = maxRetries,
    maxWaitMillis = 120_000,
    now = { clock },
    sleep = { sleeps += it; clock += it },
  )

  private class Responses(vararg val responses: HttpResponse) : HttpInterceptorChain {
    var calls = 0
    override suspend fun proceed(request: HttpRequest): HttpResponse = responses[minOf(calls++, responses.size - 1)]
  }

  private fun ok() = HttpResponse.Builder(200).build()
  private fun limited(vararg headers: Pair<String, String>) =
    HttpResponse.Builder(429).apply { headers.forEach { (name, value) -> addHeader(name, value) } }.build()

  @Test
  fun spacesRequestsASecondApart() = runBlocking {
    val limiter = limiter()
    val chain = Responses(ok())
    repeat(3) { limiter.intercept(request, chain) }
    assertEquals(listOf(1_000L, 1_000L), sleeps)
    assertEquals(3, chain.calls)
  }

  @Test
  fun waitsAsLongAsHardcoverAsksBeforeRetrying() = runBlocking {
    val chain = Responses(limited("Retry-After" to "7"), ok())
    val response = limiter().intercept(request, chain)
    assertEquals(200, response.statusCode)
    assertEquals(2, chain.calls)
    assertEquals(7_000L, sleeps.first())
  }

  @Test
  fun usesTheResetTimeWhenThereIsNoRetryAfter() = runBlocking {
    clock = 100_000
    val chain = Responses(limited("x-ratelimit-reset" to "130"), ok())
    assertEquals(200, limiter().intercept(request, chain).statusCode)
    assertEquals(30_000L, sleeps.first())
  }

  @Test
  fun givesUpAfterTheLastRetry() = runBlocking {
    val chain = Responses(limited("Retry-After" to "1"))
    assertEquals(429, limiter(maxRetries = 2).intercept(request, chain).statusCode)
    assertEquals(3, chain.calls)
  }

  @Test
  fun doesNotWaitOutTheDailyLimit() = runBlocking {
    val chain = Responses(limited("Retry-After" to "${6 * 60 * 60}"))
    assertEquals(429, limiter().intercept(request, chain).statusCode)
    assertEquals(1, chain.calls)
    assertEquals(emptyList(), sleeps)
  }
}
