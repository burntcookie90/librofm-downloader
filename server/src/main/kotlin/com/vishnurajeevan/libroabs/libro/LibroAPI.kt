package com.vishnurajeevan.libroabs.libro

import de.jensklingenberg.ktorfit.http.*
import io.ktor.client.request.HttpRequestBuilder

interface LibroAPI {
  @POST("oauth/token")
  suspend fun fetchLoginData(
    @Body loginRequest: LoginRequest
  ): TokenMetadata

  @GET("api/v10/library")
  suspend fun fetchLibrary(
    @Header("Authorization") authToken: String,
    @Query("page") page: Int = 1
  ): LibraryMetadata

  @GET("api/v10/explore/audiobook_details/{isbn}")
  suspend fun fetchAudiobookDetails(
    @Header("Authorization") authToken: String,
    @Path("isbn") isbn: String
  ): Book

  @GET("api/v10/download-manifest")
  suspend fun fetchDownloadMetadata(
    @Header("Authorization") authToken: String,
    @Query("isbn") isbn: String
  ): Mp3DownloadMetadata

  @GET("api/v10/audiobooks/{isbn}/packaged_m4b")
  suspend fun fetchM4BMetadata(
    @Header("Authorization") authToken: String,
    @Path("isbn") isbn: String,
  ): M4bMetadata

}