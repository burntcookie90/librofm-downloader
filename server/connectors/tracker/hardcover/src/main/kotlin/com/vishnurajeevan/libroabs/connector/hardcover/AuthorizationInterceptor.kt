package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain

internal class AuthorizationInterceptor(
  private val token: String,
) : HttpInterceptor {

  override suspend fun intercept(request: HttpRequest, chain: HttpInterceptorChain): HttpResponse {
    // A 401 was previously retried once, immediately, with the same token and no backoff. That retry
    // could never succeed — it only doubled every failing request against Hardcover for a token that
    // was already known bad. Surface the 401 to the caller instead.
    return chain.proceed(request.newBuilder().addHeader("Authorization", "Bearer $token").build())
  }
}