package com.vishnurajeevan.libroabs.server.route

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ClassKey
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import io.ktor.resources.Resource
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext

@Resource("/update")
class Update(val overwrite: Boolean? = false)

@Inject
@ContributesIntoMap(scope = AppScope::class)
@ClassKey(Update::class)
class UpdateRouteHandler: RouteHandler<Update> {
  context(routingContext: RoutingContext)
  override suspend fun handle(route: Update) {
    routingContext.call.respondText("Updating, overwrite: ${route.overwrite}!")
  }
}