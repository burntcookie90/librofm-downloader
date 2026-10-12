package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.db.repo.LibroFmWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.LibroFmWishlistSyncStatus
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.graph.Named
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.DownloadPart
import com.vishnurajeevan.libroabs.models.libro.LoginRequest
import com.vishnurajeevan.libroabs.models.libro.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.models.libro.PdfExtra
import com.vishnurajeevan.libroabs.models.server.M4bMetadata
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.resolveWithin
import com.vishnurajeevan.libroabs.models.server.toSafePathSegment
import com.vishnurajeevan.libroabs.storage.Storage
import com.vishnurajeevan.libroabs.storage.models.AuthToken
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import com.vishnurajeevan.libroabs.models.libro.WishlistItemSyncStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import io.ktor.client.HttpClient
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.asSink
import java.io.File

@Inject
@ContributesBinding(AppScope::class, binding = binding<LibroFmBooks>())
class LibroApiHandler(
  serverInfo: ServerInfo,
  private val libroAPI: LibroAPI,
  @Named("download") private val downloadClient: HttpClient,
  private val authTokenStorage: Storage<AuthToken>,
  private val wishlistSyncStatusRepo: LibroFmWishlistSyncStatusRepo,
  private val libroLibraryStorage: Storage<LibraryMetadata>,
  private val lfdLogger: Logger,
  @Io private val ioDispatcher: CoroutineDispatcher,
  private val dbWriter: DbWriter,
) : LibroFmBooks {
  private val dryRun = serverInfo.dryRun

  suspend fun fetchLoginData(username: String, password: String) = withContext(ioDispatcher) {
    val saved = authTokenStorage.getData()
    // Log in again when the configured account changed, otherwise the old account's library keeps syncing.
    if (saved.token.isNullOrEmpty() || saved.username != username) {
      val tokenData = libroAPI.fetchLoginData(
        LoginRequest(username = username, password = password)
      )
      if (tokenData.access_token != null) {
        authTokenStorage.update {
          it.copy(
            token = tokenData.access_token,
            username = username,
          )
        }
        println("Login success!")
      } else {
        println("Login failed!")
        throw IllegalArgumentException("failed login!")
      }
    }
  }

  private val token by lazy { runBlocking { "Bearer ${authTokenStorage.getData().token}" } }

  suspend fun fetchLibrary(page: Int = 1) = withContext(ioDispatcher) {
    libroLibraryStorage.update {
      val firstPage = libroAPI.fetchLibrary(authToken = token, page = page)

      if (firstPage.total_pages > 1) {
        var allPages = firstPage
        (2..firstPage.total_pages)
          .forEach { i ->
            val nextPage= libroAPI.fetchLibrary(token, i)
            allPages = allPages.copy(
              audiobooks = allPages.audiobooks + nextPage.audiobooks
            )
          }
        allPages
      }
      else {
        firstPage
      }
    }
  }

  override suspend fun getLocalLibrary(): LibraryMetadata = withContext(ioDispatcher) { libroLibraryStorage.getData() }

  override suspend fun fetchMp3DownloadMetadata(isbn: String): Mp3DownloadMetadata = libroAPI.fetchDownloadMetadata(token, isbn)

  override suspend fun fetchM4bMetadata(isbn: String): Result<M4bMetadata> {
    val response = libroAPI.fetchM4BMetadata(token, isbn)
    return if (response.isSuccessful) {
      Result.success(response.body()!!)
    } else {
      Result.failure(Exception("M4B Not Found!"))
    }
  }

  override suspend fun downloadM4b(m4bUrl: String, targetDirectory: File) {
    if (!dryRun) {
      lfdLogger.v("Downloading M4B: $m4bUrl")
      val url = Url(m4bUrl)
      downloadFile(url, targetDirectory.resolveWithin(m4bFilename(url).toSafePathSegment()))
    }
  }

  override suspend fun downloadMp3s(data: List<DownloadPart>, targetDirectory: File): List<File> {
    if (dryRun) return emptyList()
    return data.flatMapIndexed { index, part ->
      lfdLogger.v("downloading part ${index + 1}")
      val zip = File(targetDirectory, "part-$index.zip")
      try {
        downloadFile(Url(part.url), zip)
        withContext(ioDispatcher) { extractZip(zip, targetDirectory) }
      } finally {
        zip.delete()
      }
    }
  }

  override suspend fun downloadPdfExtras(
    isbn: String,
    data: List<PdfExtra>,
    targetDirectory: File
  ) {
    data.forEach { pdfExtra ->
      lfdLogger.v("Download PDF Extras for $isbn")
      val downloadUrl = libroAPI.fetchPdfExtraUrl(
        authToken = token,
        isbn = isbn,
        filename = pdfExtra.filename
      )
      if (!dryRun) {
        downloadFile(
          url = Url(downloadUrl.pdf_url),
          destinationFile = targetDirectory.resolveWithin(pdfExtra.filename.toSafePathSegment()),
        )
      }
    }
  }

  suspend fun syncWishlist(isbns: List<String>) = withContext(ioDispatcher) {
    isbns.minus(
      fetchWishlist()
        .audiobooks
        .map { it.isbn }
    )
      .minus(
        wishlistSyncStatusRepo.getSyncedIsbns()
      )
      .forEach { isbn ->
        lfdLogger.v("Syncing wishlist for $isbn")
        val response = libroAPI.addToWishlist(authToken = token, isbn = isbn)
        val status = if (response.isSuccessful) WishlistItemSyncStatus.SUCCESS else WishlistItemSyncStatus.FAILURE
        dbWriter.write(LibroFmWishlistSyncStatus(isbn, status))
      }
  }

  suspend fun fetchWishlist() = withContext(ioDispatcher) {
    libroAPI.fetchWishlist(token)
      .data
      .wishlist
  }

  override suspend fun fetchBookDetails(isbn: String): Book = withContext(ioDispatcher) {
    libroAPI.fetchAudiobookDetails(token, isbn).data.audiobook
  }

  private suspend fun downloadFile(url: Url, destinationFile: File) = withContext(ioDispatcher) {
    lfdLogger.v(
      """
      ----
      Downloading $url to ${destinationFile.name}
      ----
    """.trimIndent()
    )
    downloadClient.downloadTo(url, destinationFile)
  }
}
