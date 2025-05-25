package com.vishnurajeevan.libroabs.server

import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.server.route.Info
import com.vishnurajeevan.libroabs.server.route.Update
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.html.respondHtml
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import kotlinx.html.InputType
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.onClick
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.title
import kotlinx.html.unsafe
import kotlinx.serialization.json.Json

fun setupServer(
  onUpdate: suspend (overwrite: Boolean) -> Unit = {},
  serverInfo: ServerInfo,
) : EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
  return embeddedServer(
    factory = Netty,
    port = serverInfo.port,
    host = "0.0.0.0",
    module = {
      install(Resources)
      install(ContentNegotiation) {
        json(Json { })
      }
      routing {
        get<Info> {
          call.respond(
            serverInfo
          )
        }
        get<Update> {
          val overwrite = it.overwrite ?: false
          call.respondText("Updating, overwrite: $overwrite!")
          onUpdate(overwrite)
        }
        head("/") {
          call.response.headers.append(
            "App-Hash",
            "${serverInfo.hashCode()}"
          )
          call.respond("")
        }
        get("/") {
          call.respondHtml(HttpStatusCode.OK) {
            head {
              title {
                +"libro.fm Downloader"
              }
              script {
                unsafe {
                  +"""
                                        function callUpdateFunction() {
                                            const overwriteChecked = document.getElementById('overwriteCheckbox').checked;
                                            fetch('/update?overwrite=' + overwriteChecked, {
                                                method: 'GET'
                                            });
                                        }
                                    """.trimIndent()
                }
              }
            }
            body {
              p {
                +serverInfo.prettyPrint()
              }
              button {
                id = "updateButton"
                onClick = "callUpdateFunction()"
                +"Update Library"
              }
              +" "
              input(type = InputType.checkBox) {
                id = "overwriteCheckbox"
              }
              +" overwrite?"
            }
          }
        }
      }
    }
  )
}