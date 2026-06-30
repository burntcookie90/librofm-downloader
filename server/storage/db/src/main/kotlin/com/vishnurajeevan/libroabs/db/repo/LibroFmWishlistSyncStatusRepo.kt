package com.vishnurajeevan.libroabs.db.repo

import com.vishnurajeevan.libroabs.db.WishlistSyncStatusQueries
import com.vishnurajeevan.libroabs.models.graph.Io
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

interface LibroFmWishlistSyncStatusRepo {
  suspend fun getSyncedIsbns(): List<String>
}

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class RealLibroFmWishlistSyncStatusRepo(
  private val wishlistSyncStatusQueries: WishlistSyncStatusQueries,
  @Io private val ioDispatcher: CoroutineDispatcher,
): LibroFmWishlistSyncStatusRepo {

  override suspend fun getSyncedIsbns(): List<String> = withContext(ioDispatcher) {
    wishlistSyncStatusQueries.getIsbns().executeAsList()
  }
}