package com.vishnurajeevan.libroabs.db.repo

import com.vishnurajeevan.libroabs.db.DownloadHistoryQueries
import com.vishnurajeevan.libroabs.models.graph.Io
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

interface DownloadHistoryRepo {
  suspend fun isDownloaded(isbn: String): Boolean
  suspend fun downloadCount(): Long
}

@Inject
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class RealDownloadHistoryRepo(
  private val downloadHistoryQueries: DownloadHistoryQueries,
  @Io private val ioDispatcher: CoroutineDispatcher,
) : DownloadHistoryRepo {
  override suspend fun isDownloaded(isbn: String): Boolean = withContext(ioDispatcher) {
    downloadHistoryQueries.isDownloaded(isbn).executeAsOne()
  }

  override suspend fun downloadCount(): Long = withContext(ioDispatcher) {
    downloadHistoryQueries.downloadCount().executeAsOne()
  }

}