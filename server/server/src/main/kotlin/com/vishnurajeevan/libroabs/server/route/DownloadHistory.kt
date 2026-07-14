package com.vishnurajeevan.libroabs.server.route

import com.vishnurajeevan.libroabs.db.repo.DownloadHistoryRepo
import com.vishnurajeevan.libroabs.db.writer.DbWriter
import com.vishnurajeevan.libroabs.db.writer.DeleteDownloadHistoryItem
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.resources.*
import io.ktor.server.request.httpMethod
import io.ktor.server.response.*
import io.ktor.server.routing.*

@Resource("/history")
class DownloadHistory {
  @Resource("{isbn}")
  data class Isbn(val parent: DownloadHistory = DownloadHistory(), val isbn: String)
}


@Inject
@ContributesIntoMap(scope = AppScope::class)
@ClassKey(DownloadHistory::class)
class DownloadHistoryHandler(private val downloadHistoryRepo: DownloadHistoryRepo): RouteHandler<DownloadHistory> {
  context(routingContext: RoutingContext)
  override suspend fun handle(route: DownloadHistory) {
    routingContext.call.respond(downloadHistoryRepo.downloadHistory())
  }
}

@Inject
@ContributesIntoMap(scope = AppScope::class)
@ClassKey(DownloadHistory.Isbn::class)
class IsbnHandler(
  private val isbnRepo: DownloadHistoryRepo,
  private val dbWriter: DbWriter
  ): RouteHandler<DownloadHistory.Isbn> {
  context(routingContext: RoutingContext)
  override suspend fun handle(route: DownloadHistory.Isbn) {
    when (routingContext.call.request.httpMethod) {
      HttpMethod.Get -> {
        routingContext.call.respond(isbnRepo.downloadHistory(route.isbn))
      }
      HttpMethod.Delete -> {
        dbWriter.write(DeleteDownloadHistoryItem(route.isbn))
        routingContext.call.respond(HttpStatusCode.OK)
      }
    }
  }
}