package com.vishnurajeevan.libroabs.libro

import com.vishnurajeevan.libroabs.db.repo.LibroFmWishlistSyncStatusRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.LibroFmWishlistSyncStatus
import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.libro.Book
import com.vishnurajeevan.libroabs.models.libro.DownloadPart
import com.vishnurajeevan.libroabs.models.libro.LoginRequest
import com.vishnurajeevan.libroabs.models.libro.Mp3DownloadMetadata
import com.vishnurajeevan.libroabs.models.libro.PdfExtra
import com.vishnurajeevan.libroabs.models.libro.WishlistItemSyncStatus
import com.vishnurajeevan.libroabs.models.server.M4bMetadata
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.resolveWithin
import com.vishnurajeevan.libroabs.models.server.toSafePathSegment
import com.vishnurajeevan.libroabs.storage.Storage
import com.vishnurajeevan.libroabs.storage.models.AuthToken
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.binding
import io.ktor.client.HttpClient
import io.ktor.http.Url
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

  @Volatile private var credentials: Pair<String, String>? = null
  private val loginMutex = Mutex()

  suspend fun fetchLoginData(username: String, password: String) = withContext(ioDispatcher) {
    credentials = username to password
    val saved = authTokenStorage.getData()
    // Log in again when the configured account changed, otherwise the old account's library keeps syncing.
    if (saved.token.isNullOrEmpty() || saved.username != username) {
      login(username, password)
    }
  }

  private suspend fun login(username: String, password: String) {
    val tokenData = try {
      libroAPI.fetchLoginData(LoginRequest(username = username, password = password))
    } catch (e: LibroUnauthorizedException) {
      throw IllegalArgumentException("libro.fm rejected the login; check LIBRO_FM_USERNAME and LIBRO_FM_PASSWORD", e)
    }
    val accessToken = tokenData.access_token ?: throw IllegalArgumentException("libro.fm login failed: no token returned")
    authTokenStorage.update { it.copy(token = accessToken, username = username) }
    lfdLogger.i("Logged in to libro.fm as $username")
  }

  private suspend fun token(): String = "Bearer ${authTokenStorage.getData().token}"

  /** Runs [call] with the saved token. If libro.fm rejects the token, logs in again once and retries. */
  private suspend fun <T> authorized(call: suspend (token: String) -> T): T {
    val token = token()
    return try {
      call(token)
    } catch (e: LibroUnauthorizedException) {
      loginMutex.withLock {
        // Parallel downloads can all be rejected at once; only the first one logs in again.
        if (token() == token) {
          val (username, password) = credentials ?: throw e
          lfdLogger.i("libro.fm rejected the saved token, logging in again")
          login(username, password)
        }
      }
      call(token())
    }
  }

  suspend fun fetchLibrary() = withContext(ioDispatcher) {
    val firstPage = authorized { libroAPI.fetchLibrary(authToken = it, page = 1) }.requireNoError()
    val library = (2..firstPage.total_pages).fold(firstPage) { all, page ->
      val next = authorized { libroAPI.fetchLibrary(authToken = it, page = page) }.requireNoError()
      all.copy(audiobooks = all.audiobooks + next.audiobooks)
    }
    libroLibraryStorage.update { library }
  }

  /** libro.fm error bodies deserialize into an empty library; never save one of those over the real thing. */
  private fun LibraryMetadata.requireNoError() = also {
    error?.let { throw IllegalStateException("libro.fm library request failed: $it") }
  }

  override suspend fun getLocalLibrary(): LibraryMetadata = withContext(ioDispatcher) { libroLibraryStorage.getData() }

  override suspend fun fetchMp3DownloadMetadata(isbn: String): Mp3DownloadMetadata =
    authorized { libroAPI.fetchDownloadMetadata(it, isbn) }

  override suspend fun fetchM4bMetadata(isbn: String): Result<M4bMetadata> {
    val response = authorized { libroAPI.fetchM4BMetadata(it, isbn) }
    val m4b = response.body()?.takeIf { response.isSuccessful && it.m4b_url.isNotBlank() }
    return when {
      m4b != null -> Result.success(m4b)
      // An outage must not look like "this book has no M4B", or the book quietly falls back to MP3s.
      response.code == 429 || response.code >= 500 ->
        throw IOException("libro.fm answered HTTP ${response.code} when asked for the M4B of $isbn")
      else -> Result.failure(Exception("M4B Not Found!"))
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
    // Everything is unpacked aside first, so a part that fails leaves neither a half-extracted book nor a mix of old
    // and new files in the book's folder.
    val staging = File(targetDirectory, ".libro-download")
    return try {
      withContext(ioDispatcher) { staging.deleteRecursively(); staging.mkdirs() }
      val extracted = data.flatMapIndexed { index, part ->
        lfdLogger.v("downloading part ${index + 1} of ${data.size}")
        val zip = File(staging, "part-$index.zip")
        try {
          downloadFile(Url(part.url), zip)
          withContext(ioDispatcher) { extractZip(zip, File(staging, "files")) }
        } finally {
          zip.delete()
        }
      }
      withContext(ioDispatcher) { moveInto(File(staging, "files"), extracted, targetDirectory) }
    } finally {
      withContext(NonCancellable + ioDispatcher) { staging.deleteRecursively() }
    }
  }

  override suspend fun downloadPdfExtras(
    isbn: String,
    data: List<PdfExtra>,
    targetDirectory: File
  ) {
    data.forEach { pdfExtra ->
      lfdLogger.v("Download PDF Extras for $isbn")
      val downloadUrl = authorized {
        libroAPI.fetchPdfExtraUrl(
          authToken = it,
          isbn = isbn,
          filename = pdfExtra.filename
        )
      }
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
        val response = authorized { libroAPI.addToWishlist(authToken = it, isbn = isbn) }
        val status = if (response.isSuccessful) WishlistItemSyncStatus.SUCCESS else WishlistItemSyncStatus.FAILURE
        dbWriter.write(LibroFmWishlistSyncStatus(isbn, status))
      }
  }

  suspend fun fetchWishlist() = withContext(ioDispatcher) {
    authorized { libroAPI.fetchWishlist(it) }
      .data
      .wishlist
  }

  override suspend fun fetchBookDetails(isbn: String): Book = withContext(ioDispatcher) {
    authorized { libroAPI.fetchAudiobookDetails(it, isbn) }.data.audiobook
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
