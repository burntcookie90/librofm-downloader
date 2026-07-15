package com.vishnurajeevan.libroabs.server.route

import com.vishnurajeevan.libroabs.models.server.ServerInfo
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import io.ktor.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

@Resource("/info")
class Info

@Inject
@ContributesIntoMap(scope = AppScope::class)
@ClassKey(Info::class)
class InfoRouteHandler(private val serverInfo: ServerInfo): RouteHandler<Info> {
  context(routingContext: RoutingContext)
  override suspend fun handle(route: Info) {
    routingContext.call.respond(serverInfo)
  }
}