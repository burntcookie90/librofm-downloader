package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain

internal class AuthorizationInterceptor(
  token: String,
) : HttpInterceptor {
  // Hardcover shows the token as "Bearer ey…"; accept it pasted either way.
  private val token = token.trim().removePrefix("Bearer ").trim()

  override suspend fun intercept(request: HttpRequest, chain: HttpInterceptorChain): HttpResponse =
    chain.proceed(request.newBuilder().addHeader("Authorization", "Bearer $token").build())
}
