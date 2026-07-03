package com.vishnurajeevan.libroabs.storage

import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.Io
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.storage.models.AuthToken
import com.vishnurajeevan.libroabs.storage.models.LibraryMetadata
import com.vishnurajeevan.libroabs.storage.models.LibroDownloadHistory
import com.vishnurajeevan.libroabs.storage.models.WishlistSyncHistory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.serializer
import java.io.File

@ContributesTo(AppScope::class)
@SingleIn(AppScope::class)
interface StorageComponent {
  @Provides
  fun downloadHistory(
    serverInfo: ServerInfo,
    logger: Logger,
    @Io dispatcher: CoroutineDispatcher
  ): Storage<LibroDownloadHistory> =
    RealStorage.Factory<LibroDownloadHistory>()
      .create(
        file = File("${serverInfo.dataDir}/download_history.json"),
        initial = LibroDownloadHistory(),
        serializer = serializer(),
        dispatcher = dispatcher,
        logger = logger
      )

  @Provides
  fun authToken(
    serverInfo: ServerInfo,
    lfdLogger: Logger,
    @Io dispatcher: CoroutineDispatcher
  ): Storage<AuthToken> = RealStorage.Factory<AuthToken>()
    .create(
      file = File("${serverInfo.dataDir}/auth_token.json"),
      initial = AuthToken(),
      serializer = serializer(),
      dispatcher = dispatcher,
      logger = lfdLogger
    )

  @Provides
  fun wishlistSyncHistory(
    serverInfo: ServerInfo,
    lfdLogger: Logger,
    @Io dispatcher: CoroutineDispatcher
  ): Storage<WishlistSyncHistory> = RealStorage.Factory<WishlistSyncHistory>()
    .create(
      file = File("${serverInfo.dataDir}/wishlist_sync_history.json"),
      initial = WishlistSyncHistory(emptyMap()),
      serializer = serializer(),
      dispatcher = dispatcher,
      logger = lfdLogger
    )

  @Provides
  fun libraryMetadata(
    serverInfo: ServerInfo,
    lfdLogger: Logger,
    @Io dispatcher: CoroutineDispatcher
  ): Storage<LibraryMetadata> = RealStorage.Factory<LibraryMetadata>()
    .create(
      file = File("${serverInfo.dataDir}/libro_library.json"),
      initial = LibraryMetadata(),
      serializer = serializer(),
      dispatcher = dispatcher,
      logger = lfdLogger
    )
}