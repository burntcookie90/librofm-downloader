package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.models.server.M4bMetadata
import com.vishnurajeevan.libroabs.models.libro.BookDetailsResponse
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import com.vishnurajeevan.libroabs.models.libro.LoginRequest
import com.vishnurajeevan.libroabs.models.libro.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.models.libro.PdfExtraResponse
import com.vishnurajeevan.libroabs.models.libro.TokenMetadata
import com.vishnurajeevan.libroabs.models.libro.WishlistResponse
import de.jensklingenberg.ktorfit.Response
import de.jensklingenberg.ktorfit.http.*

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

  @GET("api/v10/library/{isbn}/pdf_extra_url")
  suspend fun fetchPdfExtraUrl(
    @Header("Authorization") authToken: String,
    @Path("isbn") isbn: String,
    @Query("filename") filename: String
  ): PdfExtraResponse

  @GET("api/v10/explore/audiobook_details/{isbn}")
  suspend fun fetchAudiobookDetails(
    @Header("Authorization") authToken: String,
    @Path("isbn") isbn: String
  ): BookDetailsResponse

  @GET("api/v10/download-manifest")
  suspend fun fetchDownloadMetadata(
    @Header("Authorization") authToken: String,
    @Query("isbn") isbn: String
  ): Mp3DownloadMetadata

  @GET("api/v10/audiobooks/{isbn}/packaged_m4b")
  suspend fun fetchM4BMetadata(
    @Header("Authorization") authToken: String,
    @Path("isbn") isbn: String,
  ): Response<M4bMetadata>

  @GET("api/v10/explore/wishlist")
  suspend fun fetchWishlist(
    @Header("Authorization") authToken: String,
  ): WishlistResponse

  @POST("api/v10/explore/wishlist/{isbn}")
  suspend fun addToWishlist(
    @Header("Authorization") authToken: String,
    @Path("isbn") isbn: String
  ): Response<Unit>
}