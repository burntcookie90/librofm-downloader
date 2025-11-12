package com.vishnurajeevan.libroabs.connector.hardcover

import com.apollographql.apollo.api.http.HttpRequest
import com.apollographql.apollo.api.http.HttpResponse
import com.apollographql.apollo.network.http.HttpInterceptor
import com.apollographql.apollo.network.http.HttpInterceptorChain

internal class AuthorizationInterceptor(
  private val token: String,
) : HttpInterceptor {

  override suspend fun intercept(request: HttpRequest, chain: HttpInterceptorChain): HttpResponse {
    val newRequest = request.newBuilder().addHeader("Authorization", "Bearer $token").build()
    val response = chain.proceed(newRequest)

    return if (response.statusCode == 401) {
      chain.proceed(newRequest)
    } else {
      response
    }
  }
}