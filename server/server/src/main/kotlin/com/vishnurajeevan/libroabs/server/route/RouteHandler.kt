package com.vishnurajeevan.libroabs.server.route

import dev.zacsweers.metro.DefaultBinding
import io.ktor.server.routing.*

@DefaultBinding<RouteHandler<*>>
fun interface RouteHandler<T> {
  context(routingContext: RoutingContext)
  suspend fun handle(route: T)
}