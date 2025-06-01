package com.vishnurajeevan.libroabs.db.repo

import com.vishnurajeevan.libroabs.db.WishlistSyncStatusQueries
import com.vishnurajeevan.libroabs.models.graph.Io
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

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