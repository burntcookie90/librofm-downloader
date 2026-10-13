package com.vishnurajeevan.libroabs.server.ui

import com.vishnurajeevan.libroabs.models.server.AttemptStatus
import com.vishnurajeevan.libroabs.models.server.DownloadAttempt
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.SyncSnapshot
import com.vishnurajeevan.libroabs.models.server.SyncTrigger
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.style

fun FlowContent.dashboardPage(
  stats: DashboardStats,
  sync: SyncSnapshot,
  recent: List<DownloadAttempt>,
  info: ServerInfo,
) {
  pageHead("Dashboard", "Signed in to libro.fm as ${info.libroUserName}") {
    a("/ui/library", classes = "btn") { +"Browse library" }
  }
  dashboardPanel(stats, sync, recent, info)
}

fun FlowContent.dashboardPanel(
  stats: DashboardStats,
  sync: SyncSnapshot,
  recent: List<DownloadAttempt>,
  info: ServerInfo,
) {
  val live = stats.active > 0 || sync.running
  div("grid") {
    id = "dashboard-panel"
    if (live) {
      attributes["hx-get"] = "/ui/dashboard/panel"
      attributes["hx-trigger"] = "every 3s"
      attributes["hx-swap"] = "outerHTML"
    }
    div("stats") {
      statCard("Books", stats.totalBooks, "/ui/library")
      statCard("Downloaded", stats.downloaded, "/ui/library?filter=DOWNLOADED")
      statCard("Not downloaded", stats.notDownloaded, "/ui/library?filter=MISSING")
      statCard("Failed", stats.failed, "/ui/library?filter=FAILED", if (stats.failed > 0) "bad" else "")
      statCard("In progress", stats.active, "/ui/queue", if (stats.active > 0) "busy" else "")
    }
    if (stats.totalBooks > 0) {
      div("progress") {
        attributes["role"] = "progressbar"
        attributes["aria-label"] = "Library downloaded"
        attributes["aria-valuenow"] = (stats.downloaded * 100 / stats.totalBooks).toString()
        attributes["aria-valuemin"] = "0"
        attributes["aria-valuemax"] = "100"
        span { style = "width:${stats.downloaded * 100 / stats.totalBooks}%" }
      }
    }
    div("grid two") {
      div("card") {
        syncSummary(sync, info)
      }
      div("card") {
        div("page-head") {
          h2 { +"Recent activity" }
          a("/ui/history", classes = "small") { +"All history →" }
        }
        if (recent.isEmpty()) {
          emptyState("No downloads yet", "Run a sync to download your library.")
        } else {
          div("rows") {
            recent.forEach { attemptRow(it, view = "queue", withActions = false) }
          }
        }
      }
    }
  }
}

private fun FlowContent.statCard(label: String, value: Int, href: String, kind: String = "") {
  a(href, classes = "stat $kind".trim()) {
    span("n") { +value.toString() }
    span("l") { +label }
  }
}

fun SyncTrigger.label() = when (this) {
  SyncTrigger.STARTUP -> "app start"
  SyncTrigger.SCHEDULED -> "schedule"
  SyncTrigger.MANUAL -> "web UI"
  SyncTrigger.API -> "API request"
}

fun syncIntervalLabel(interval: String) = when (interval) {
  "h" -> "every hour"
  "d" -> "every day"
  "w" -> "every week"
  else -> interval
}

/** Status of the library sync and the button to run one. Shared by the dashboard and the sync page. */
fun FlowContent.syncSummary(sync: SyncSnapshot, info: ServerInfo, view: String = "dashboard") {
  val target = if (view == "sync") "#sync-panel" else "#dashboard-panel"
  div("page-head") {
    h2 { +"Library sync" }
    if (sync.running) badge("Syncing", "info", dot = true) else badge("Idle")
  }
  dl("kv") {
    dt { +"Schedule" }
    dd { +syncIntervalLabel(info.syncInterval) }
    if (sync.running) {
      dt { +"Started" }
      dd {
        sync.startedAt?.let { timestamp(it, relative = true) }
        sync.trigger?.let { +" by ${it.label()}" }
      }
    }
    dt { +"Last run" }
    dd {
      val finished = sync.lastFinishedAt
      if (finished == null) {
        +"Not yet this session"
      } else {
        timestamp(finished, relative = true)
        sync.lastDurationMs?.let { +" · took ${formatElapsed(it)}" }
        sync.lastTrigger?.let { +" · ${it.label()}" }
      }
    }
    if (sync.lastError != null && !sync.running) {
      dt { +"Result" }
      dd("err-text") { +"Finished with an error: ${sync.lastError}" }
    } else if (sync.lastFinishedAt != null && !sync.running) {
      dt { +"Result" }
      dd { badge("Succeeded", "ok") }
    }
  }
  form {
    id = "sync-form"
    div("inline-form") {
      label("check") {
        input(type = InputType.checkBox, name = "overwrite") { value = "true" }
        span("small") { +"Re-download everything" }
      }
      button(classes = "btn primary", type = ButtonType.button) {
        attributes["hx-post"] = "/ui/sync/run?view=$view"
        attributes["hx-include"] = "#sync-form"
        attributes["hx-target"] = target
        attributes["hx-swap"] = "outerHTML"
        if (sync.running) attributes["disabled"] = "disabled"
        icon(Icon.SYNC, 16)
        +"Sync now"
      }
    }
  }
}

// ---- sync page ----

fun FlowContent.syncPage(sync: SyncSnapshot, info: ServerInfo, wishlist: WishlistOverview) {
  pageHead("Sync", "Checks libro.fm for new books, queues downloads and updates your tracker.")
  syncPanel(sync, info, wishlist)
}

fun FlowContent.syncPanel(sync: SyncSnapshot, info: ServerInfo, wishlist: WishlistOverview) {
  div("grid") {
    id = "sync-panel"
    attributes["hx-get"] = "/ui/sync/panel"
    attributes["hx-trigger"] = if (sync.running) "every 2s" else "every 15s"
    attributes["hx-swap"] = "outerHTML"
    div("card") { syncSummary(sync, info, view = "sync") }
    div("grid two") {
      div("card") {
        h2 { +"libro.fm wishlist" }
        p("muted small") { +"Books added to your libro.fm wishlist from your tracker." }
        syncedList(wishlist.libroSynced, "No wishlist books have been synced.")
      }
      div("card") {
        h2 { +"Tracker (Hardcover)" }
        if (!wishlist.trackerEnabled) {
          p("muted") { +"Not configured. Set HARDCOVER_TOKEN to sync your library and wishlist with Hardcover." }
        } else {
          dl("kv") {
            dt { +"Mode" }
            dd { +trackerModeLabel(info.hardcoverSyncMode) }
          }
          h3 { +"Wishlist items synced" }
          syncedList(wishlist.trackerSynced, "No wishlist books have been synced.")
        }
      }
    }
  }
}

fun trackerModeLabel(mode: TrackerSyncMode) = when (mode) {
  TrackerSyncMode.LIBRO_WISHLISTS_TO_HARDCOVER -> "libro.fm wishlist → Hardcover"
  TrackerSyncMode.LIBRO_OWNED_TO_HARDCOVER -> "libro.fm owned → Hardcover"
  TrackerSyncMode.LIBRO_ALL_TO_HARDCOVER -> "libro.fm wishlist and owned → Hardcover"
  TrackerSyncMode.HARDCOVER_WANT_TO_READ_TO_LIBRO -> "Hardcover want-to-read → libro.fm"
  TrackerSyncMode.ALL -> "All directions"
}

private fun FlowContent.syncedList(items: Map<String, Boolean>, empty: String) {
  if (items.isEmpty()) {
    p("muted small") { +empty }
    return
  }
  val ok = items.count { it.value }
  p("small") {
    +"$ok succeeded"
    if (ok < items.size) +" · ${items.size - ok} failed"
  }
  div("rows") {
    items.entries.sortedBy { it.value }.take(50).forEach { (isbn, success) ->
      div("row") {
        span("mono") { +isbn }
        div { if (success) badge("Synced", "ok") else badge("Failed", "bad") }
      }
    }
    if (items.size > 50) p("muted small") { +"…and ${items.size - 50} more." }
  }
}

// ---- settings ----

fun FlowContent.settingsPage(info: ServerInfo) {
  pageHead("Settings", "Configured with environment variables or command-line options. Change them and restart the container.")
  div("grid two") {
    settingsCard(
      "Account",
      "libro.fm username" to info.libroUserName,
      "Web UI password" to if (info.webUiPassword.isNullOrEmpty()) "Not set — anyone who can reach this port has access" else "Set",
    )
    settingsCard(
      "Downloads",
      "Format" to info.format.label(),
      "Path pattern" to info.pathPattern,
      "Parallel downloads" to info.parallelCount.toString(),
      "Rename chapters" to yesNo(info.renameChapters),
      "Write title tag" to yesNo(info.writeTitleTag),
      "Download PDF extras" to yesNo(info.downloadExtras),
      "Audio quality (conversion)" to info.audioQuality,
      "Library limit" to if (info.limit == -1) "None" else info.limit.toString(),
      "Dry run" to yesNo(info.dryRun),
    )
    settingsCard(
      "Schedule",
      "Sync interval" to syncIntervalLabel(info.syncInterval),
    )
    settingsCard(
      "Storage",
      "Data directory" to info.dataDir,
      "Media directory" to info.mediaDir,
    )
    settingsCard(
      "Integrations",
      "Hardcover tracker" to if (info.trackerToken.isNullOrEmpty()) "Disabled" else "Enabled",
      "Tracker sync mode" to trackerModeLabel(info.hardcoverSyncMode),
      "Skipped ISBNs" to info.skipTrackingIsbns.joinToString(", ").ifEmpty { "None" },
      "Health check" to if (info.healthCheckId.isNullOrEmpty()) "Disabled" else "${info.healthCheckHost}/…",
      "Audiobookshelf" to (info.audiobookshelf?.let { "${it.url.substringAfter("://").substringBefore('/')} (${it.mode.name.lowercase()})" }
        ?: if (info.writesAbsMetadataFiles) "Metadata files only" else "Disabled"),
      "Webhooks" to (info.webhookUrls.joinToString(", ") { it.substringAfter("://").substringBefore('/') }.ifEmpty { "None" }),
    )
    settingsCard(
      "Server",
      "Port" to info.port.toString(),
      "Log level" to info.logLevel.name,
      "ffmpeg" to info.ffmpegPath,
      "ffprobe" to info.ffprobePath,
    )
  }
}

private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

private fun FlowContent.settingsCard(title: String, vararg rows: Pair<String, String>) {
  div("card") {
    h2 { +title }
    dl("kv") {
      rows.forEach { (k, v) ->
        dt { +k }
        dd { +v }
      }
    }
  }
}

// ---- login ----

fun FlowContent.loginForm(error: String?, next: String) {
  div("login") {
    form(action = "/ui/login", method = kotlinx.html.FormMethod.post, classes = "card") {
      div("brand") {
        icon(Icon.HEADPHONES, 28)
        span { +"libro.fm Downloader" }
      }
      if (error != null) div("alert bad") { attributes["role"] = "alert"; +error }
      input(type = InputType.hidden, name = "next") { value = next }
      label("field") {
        span { +"Password" }
        input(type = InputType.password, name = "password") {
          attributes["autocomplete"] = "current-password"
          attributes["autofocus"] = "autofocus"
          required = true
        }
      }
      button(classes = "btn primary", type = ButtonType.submit) { +"Sign in" }
    }
  }
}
