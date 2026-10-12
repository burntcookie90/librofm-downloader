package com.vishnurajeevan.libroabs.server

import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.server.route.DownloadHistory
import com.vishnurajeevan.libroabs.server.route.RouteHandler
import com.vishnurajeevan.libroabs.server.route.Update
import com.vishnurajeevan.libroabs.server.ui.WebUiBackend
import com.vishnurajeevan.libroabs.server.ui.installWebUi
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import org.slf4j.event.Level
import kotlin.reflect.KClass

@Suppress("UNCHECKED_CAST")
fun setupServer(
  onUpdate: suspend (overwrite: Boolean) -> Unit = {},
  serverInfo: ServerInfo,
  webUiBackend: WebUiBackend? = null,
  routeHandlerMap: Map<KClass<*>, RouteHandler<*>>,
) : EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
  return embeddedServer(
    factory = Netty,
    port = serverInfo.port,
    host = "0.0.0.0",
    module = {
      install(Resources)
      install(CallLogging) {
        level = when (serverInfo.logLevel) {
          ApplicationLogLevel.NONE -> Level.WARN
          ApplicationLogLevel.INFO -> Level.INFO
          ApplicationLogLevel.VERBOSE -> Level.DEBUG
        }
      }
      install(ContentNegotiation) {
        json()
      }
      if (webUiBackend != null) installWebUi(webUiBackend)
      routing {
        get<Update> {
          routeHandlerMap.handleRoute(it)
          onUpdate(it.overwrite ?: false)
        }

        get<DownloadHistory> { routeHandlerMap.handleRoute(it) }
        get<DownloadHistory.Isbn> { routeHandlerMap.handleRoute(it) }
        delete<DownloadHistory.Isbn> { routeHandlerMap.handleRoute(it) }

        // Unauthenticated uptime check; it must not reveal anything derived from the configuration.
        head("/") {
          call.respond("")
        }
        get("/") {
          call.respondRedirect("/ui")
        }
      }
    }
  )
}

@Suppress("UNCHECKED_CAST")
context(routingContext: RoutingContext)
suspend inline fun <reified T: Any> Map<KClass<*>, RouteHandler<*>>.handleRoute(route: T) {
  (getValue(T::class) as RouteHandler<T>).handle(route)
}