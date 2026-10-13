package com.vishnurajeevan.libroabs.server.ui

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.FlowContent
import kotlinx.html.HEAD
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.p
import kotlinx.html.head
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.lang
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.script
import kotlinx.html.span
import kotlinx.html.title

enum class NavItem(val path: String, val label: String, val icon: Icon) {
  DASHBOARD("/ui", "Dashboard", Icon.HOME),
  LIBRARY("/ui/library", "Library", Icon.LIBRARY),
  QUEUE("/ui/queue", "Queue", Icon.QUEUE),
  HISTORY("/ui/history", "History", Icon.HISTORY),
  SYNC("/ui/sync", "Sync", Icon.SYNC),
  SETTINGS("/ui/settings", "Settings", Icon.SETTINGS),
}

private const val ASSET_VERSION = "2"

private fun HEAD.assets(pageTitle: String) {
  meta(charset = "utf-8")
  meta(name = "viewport", content = "width=device-width, initial-scale=1, viewport-fit=cover")
  meta(name = "color-scheme", content = "light dark")
  meta(name = "robots", content = "noindex")
  title("$pageTitle · libro.fm Downloader")
  link(rel = "stylesheet", href = "/ui/static/app.css?v=$ASSET_VERSION")
  link(rel = "icon", href = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='none' stroke='%230f766e' stroke-width='2.5' stroke-linecap='round'%3E%3Cpath d='M4 15v-3a8 8 0 0 1 16 0v3'/%3E%3Crect x='3' y='14' width='4' height='7' rx='1.5'/%3E%3Crect x='17' y='14' width='4' height='7' rx='1.5'/%3E%3C/svg%3E")
  script(src = "/ui/static/htmx.min.js?v=$ASSET_VERSION") { attributes["defer"] = "defer" }
  script(src = "/ui/static/app.js?v=$ASSET_VERSION") { attributes["defer"] = "defer" }
}

suspend fun ApplicationCall.respondPage(
  title: String,
  active: NavItem?,
  status: HttpStatusCode = HttpStatusCode.OK,
  authEnabled: Boolean = false,
  content: FlowContent.() -> Unit,
) {
  respondHtml(status) {
    lang = "en"
    head { assets(title) }
    body {
      header("topbar") {
        a("/ui", classes = "brand") {
          icon(Icon.HEADPHONES, 24)
          span { +"libro.fm Downloader" }
        }
        nav("nav") {
          attributes["aria-label"] = "Main"
          NavItem.entries.forEach { item ->
            a(item.path) {
              if (item == active) attributes["aria-current"] = "page"
              icon(item.icon, 22)
              span { +item.label }
            }
          }
        }
        div("chip-wrap") {
          div {
            attributes["hx-get"] = "/ui/status"
            attributes["hx-trigger"] = "load, every 3s"
            attributes["hx-swap"] = "innerHTML"
            statusChip(activeCount = 0, syncing = false)
          }
        }
        if (authEnabled) {
          button(classes = "btn small") {
            attributes["type"] = "button"
            attributes["hx-post"] = "/ui/logout"
            +"Log out"
          }
        }
      }
      main { content() }
      div { id = "toasts"; attributes["aria-live"] = "polite" }
    }
  }
}

fun FlowContent.statusChip(activeCount: Int, syncing: Boolean) {
  when {
    activeCount > 0 -> a("/ui/queue", classes = "chip busy") { +"${pluralize(activeCount, "download")} active" }
    syncing -> a("/ui/sync", classes = "chip busy") { +"Syncing…" }
    else -> span("chip") { +"Idle" }
  }
}

fun FlowContent.pageHead(title: String, subtitle: String? = null, actions: FlowContent.() -> Unit = {}) {
  div("page-head") {
    div {
      h1 { +title }
      if (subtitle != null) p("muted small") { +subtitle }
    }
    div("actions") { actions() }
  }
}

/** A page without the application chrome, used for sign-in. */
suspend fun ApplicationCall.respondBarePage(
  title: String,
  status: HttpStatusCode = HttpStatusCode.OK,
  content: FlowContent.() -> Unit,
) {
  respondHtml(status) {
    lang = "en"
    head { assets(title) }
    body {
      content()
      div { id = "toasts"; attributes["aria-live"] = "polite" }
    }
  }
}
