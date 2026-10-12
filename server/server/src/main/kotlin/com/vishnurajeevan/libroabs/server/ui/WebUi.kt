package com.vishnurajeevan.libroabs.server.ui

import com.vishnurajeevan.libroabs.models.server.AttemptStatus
import com.vishnurajeevan.libroabs.models.server.BookFormat
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.ParametersBuilder
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.html.respondHtmlFragment
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.origin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.html.FlowContent
import kotlinx.html.div
import kotlinx.html.p

/**
 * Installs the browser UI under `/ui` and, when a web UI password is configured, guards it and the JSON API.
 */
fun Application.installWebUi(backend: WebUiBackend) {
  val password = backend.serverInfo.webUiPassword?.takeIf { it.isNotEmpty() }
  val signer = password?.let { SessionSigner(it) }
  val throttle = LoginThrottle()

  installGuards(signer, throttle)
  install(WebUiErrorPages)

  routing {
    staticResources("/ui/static", "webui")
    get("/ui/login") { loginPage(signer, null) }
    post("/ui/login") { handleLogin(signer, throttle) }
    post("/ui/logout") {
      call.response.cookies.append(sessionCookie("", 0, call.isHttps()))
      call.response.headers.append("HX-Redirect", "/ui/login")
      call.respond(HttpStatusCode.OK)
    }
    route("/ui") {
      uiRoutes(backend, authEnabled = signer != null)
    }
  }
}

// ---------------------------------------------------------------------------------------------
// auth
// ---------------------------------------------------------------------------------------------

private fun Application.installGuards(signer: SessionSigner?, throttle: LoginThrottle) {
  intercept(ApplicationCallPipeline.Plugins) {
    val path = call.request.path()
    val method = call.request.httpMethod
    // The JSON API is for scripts (and GET /update changes state), and another site's page posting wrong passwords to
    // the login form would lock the viewer out, so refuse both when a browser makes them on another site's behalf.
    val api = !path.startsWith("/ui") && path != "/"
    if ((api || (path == "/ui/login" && method == HttpMethod.Post)) && call.isCrossSite()) {
      call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
      finish()
      return@intercept
    }
    if (signer != null) {
      val public = path.startsWith("/ui/static/") || path == "/ui/login" || (path == "/" && method == HttpMethod.Head)
      if (!public && !call.isAuthorized(signer, throttle)) {
        call.rejectUnauthenticated(path)
        finish()
        return@intercept
      }
    }
    // Browsers must go through htmx (a custom header) to change state, which cross-site forms cannot set.
    val mutating = method == HttpMethod.Post || method == HttpMethod.Put || method == HttpMethod.Delete
    if (mutating && path.startsWith("/ui/") && path != "/ui/login" && !call.isHtmx()) {
      call.respondText("Forbidden", status = HttpStatusCode.Forbidden)
      finish()
    }
  }
}

/** True for requests a browser makes on behalf of another site (links, images, forms, fetch). Scripts send neither header. */
private fun ApplicationCall.isCrossSite(): Boolean {
  val fetchSite = request.headers["Sec-Fetch-Site"]
  if (fetchSite != null) return fetchSite == "cross-site" || fetchSite == "same-site"
  val origin = request.headers[HttpHeaders.Origin] ?: return false
  return runCatching { java.net.URI(origin).host }.getOrNull() != request.origin.serverHost
}

private fun ApplicationCall.isAuthorized(signer: SessionSigner, throttle: LoginThrottle): Boolean {
  if (signer.verify(request.cookies[SessionSigner.COOKIE_NAME])) return true
  val bearer = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
  val key = bearer ?: request.headers["X-Api-Key"] ?: return false
  return throttle.attempt(request.origin.remoteHost) { signer.passwordMatches(key) }
}

private suspend fun ApplicationCall.rejectUnauthenticated(path: String) {
  when {
    isHtmx() -> {
      response.headers.append("HX-Redirect", "/ui/login")
      respond(HttpStatusCode.Unauthorized)
    }

    path == "/" || path.startsWith("/ui") -> {
      val next = if (request.httpMethod == HttpMethod.Get && path.startsWith("/ui")) "?next=${java.net.URLEncoder.encode(path, Charsets.UTF_8)}" else ""
      respondRedirect("/ui/login$next")
    }

    else -> {
      response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
      respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
    }
  }
}

/**
 * Whether the browser reached us over HTTPS, including through a TLS-terminating reverse proxy. Only used to mark
 * cookies Secure, so a client forging X-Forwarded-Proto can only make its own cookie stricter.
 */
private fun ApplicationCall.isHttps() =
  request.origin.scheme == "https" || request.headers["X-Forwarded-Proto"]?.substringBefore(',')?.trim().equals("https", ignoreCase = true)

private fun sessionCookie(value: String, maxAge: Long, secure: Boolean) = Cookie(
  name = SessionSigner.COOKIE_NAME,
  value = value,
  maxAge = maxAge.toInt(),
  path = "/",
  httpOnly = true,
  secure = secure,
  extensions = mapOf("SameSite" to "Strict"),
)

private suspend fun RoutingContext.loginPage(signer: SessionSigner?, error: String?) {
  if (signer == null) {
    call.respondRedirect("/ui")
    return
  }
  val next = safeNext(call.request.queryParameters["next"])
  call.respondBarePage("Sign in", if (error != null) HttpStatusCode.Unauthorized else HttpStatusCode.OK) {
    loginForm(error, next)
  }
}

private suspend fun RoutingContext.handleLogin(signer: SessionSigner?, throttle: LoginThrottle) {
  if (signer == null) {
    call.respondRedirect("/ui")
    return
  }
  val form = call.receiveParameters()
  val client = call.request.origin.remoteHost
  if (throttle.isLocked(client)) {
    call.respondBarePage("Sign in", HttpStatusCode.TooManyRequests) {
      loginForm("Too many incorrect attempts. Try again in a few minutes.", safeNext(form["next"]))
    }
  } else if (throttle.attempt(client) { signer.passwordMatches(form["password"]) }) {
    call.response.cookies.append(sessionCookie(signer.issue(), signer.ttl, call.isHttps()))
    call.respondRedirect(safeNext(form["next"]))
  } else {
    val next = safeNext(form["next"])
    call.respondBarePage("Sign in", HttpStatusCode.Unauthorized) { loginForm("Incorrect password.", next) }
  }
}

/** Only allow redirects to pages of this UI. */
internal fun safeNext(next: String?): String =
  if (next != null && next.startsWith("/ui") && !next.startsWith("//") && !next.contains("\\") && !next.contains("://")) next else "/ui"

// ---------------------------------------------------------------------------------------------
// error pages
// ---------------------------------------------------------------------------------------------

private val WebUiErrorPages = createApplicationPlugin("WebUiErrorPages") {
  on(CallFailed) { call, cause ->
    if (cause is CancellationException) return@on
    val path = call.request.path()
    if (!path.startsWith("/ui")) return@on
    call.application.log.error("Web UI request failed: ${call.request.httpMethod.value} $path", cause)
    if (call.isHtmx()) {
      call.response.headers.append("HX-Trigger", toastTrigger("Something went wrong. Check the server logs for details.", "bad"))
      call.respond(HttpStatusCode.InternalServerError)
    } else {
      call.respondPage("Error", null, HttpStatusCode.InternalServerError) {
        pageHead("Something went wrong")
        div("card") {
          p { +"An unexpected error occurred. Check the server logs for details." }
        }
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------------------------

private fun ApplicationCall.isHtmx() = request.headers["HX-Request"] == "true"

/** Fragment responses are for htmx requests, except when htmx restores a page from history. */
private fun ApplicationCall.wantsFragment() = isHtmx() && request.headers["HX-History-Restore-Request"] != "true"

/** Query string and form body merged. */
private suspend fun ApplicationCall.params(): Parameters {
  val form = if (request.httpMethod == HttpMethod.Get) Parameters.Empty else runCatching { receiveParameters() }.getOrDefault(Parameters.Empty)
  return ParametersBuilder().apply {
    appendAll(request.queryParameters)
    appendAll(form)
  }.build()
}

private fun ApplicationCall.toast(message: String, kind: String = "ok") {
  response.headers.append("HX-Trigger", toastTrigger(message, kind))
}

private fun Parameters.format(): BookFormat? =
  this["format"]?.takeIf { it.isNotBlank() }?.let { name -> BookFormat.entries.firstOrNull { it.name == name } }

private suspend fun ApplicationCall.fragment(block: FlowContent.() -> Unit) {
  response.headers.append(HttpHeaders.Vary, "HX-Request")
  respondHtmlFragment { block() }
}

private suspend fun ApplicationCall.notFoundPage(what: String) {
  if (isHtmx()) {
    toast("$what not found.", "bad")
    respond(HttpStatusCode.NotFound)
  } else {
    respondPage("Not found", null, HttpStatusCode.NotFound) {
      pageHead("Not found")
      div("card") { p { +"$what could not be found." } }
    }
  }
}

private fun describe(outcome: EnqueueOutcome, title: String? = null): Pair<String, String> {
  val name = title?.let { "“${it.truncate(40)}”" } ?: "Download"
  return when (outcome) {
    is EnqueueOutcome.Queued -> "$name queued." to "ok"
    is EnqueueOutcome.AlreadyActive -> "$name is already queued or downloading." to "ok"
    EnqueueOutcome.NotFound -> "Book not found." to "bad"
    is EnqueueOutcome.Failure -> outcome.message to "bad"
  }
}

// ---------------------------------------------------------------------------------------------
// routes
// ---------------------------------------------------------------------------------------------

private fun Route.uiRoutes(backend: WebUiBackend, authEnabled: Boolean) {
  suspend fun ApplicationCall.page(title: String, active: NavItem?, content: FlowContent.() -> Unit) =
    respondPage(title, active, authEnabled = authEnabled, content = content)

  // ---- dashboard ----
  suspend fun ApplicationCall.dashboardFragment() {
    val stats = backend.stats()
    val sync = backend.syncSnapshot()
    val recent = backend.recentAttempts(6)
    fragment { dashboardPanel(stats, sync, recent, backend.serverInfo) }
  }

  get {
    val stats = backend.stats()
    val sync = backend.syncSnapshot()
    val recent = backend.recentAttempts(6)
    call.page("Dashboard", NavItem.DASHBOARD) { dashboardPage(stats, sync, recent, backend.serverInfo) }
  }
  get("dashboard/panel") { call.dashboardFragment() }

  get("status") {
    val active = backend.activeAttempts().size
    val syncing = backend.syncSnapshot().running
    call.fragment { statusChip(active, syncing) }
  }

  // ---- library ----
  suspend fun ApplicationCall.libraryFragment(params: Parameters) {
    val query = LibraryQuery.parse(params)
    val page = backend.libraryEntries().query(query)
    fragment { libraryResults(query, page) }
  }

  get("library") {
    val query = LibraryQuery.parse(call.request.queryParameters)
    val page = backend.libraryEntries().query(query)
    if (call.wantsFragment()) {
      call.fragment { libraryResults(query, page) }
    } else {
      call.response.headers.append(HttpHeaders.Vary, "HX-Request")
      call.page("Library", NavItem.LIBRARY) { libraryPage(query, page) }
    }
  }
  get("library/row/{isbn}") {
    val entry = backend.libraryEntry(call.parameters["isbn"].orEmpty())
    if (entry == null) call.notFoundPage("Book") else call.fragment { bookRow(entry, selectable = true) }
  }
  post("library/download") {
    val params = call.params()
    val isbns = params.getAll("isbn").orEmpty()
    if (isbns.isEmpty()) {
      call.toast("Select at least one book first.", "bad")
    } else {
      val queued = backend.enqueueDownloads(isbns, params.format())
      call.toast(if (queued == 0) "Those books are already queued." else "Queued ${pluralize(queued, "book")} for download.")
    }
    call.libraryFragment(params)
  }
  post("library/download-missing") {
    val params = call.params()
    val queued = backend.downloadMissing()
    call.toast(if (queued == 0) "Nothing to download." else "Queued ${pluralize(queued, "book")} for download.")
    call.libraryFragment(params)
  }

  // ---- book ----
  suspend fun ApplicationCall.bookLiveFragment(isbn: String) {
    val entry = backend.libraryEntry(isbn)
    if (entry == null) {
      notFoundPage("Book")
    } else {
      val attempts = backend.attemptsFor(isbn)
      fragment { bookLive(entry, attempts, backend.serverInfo.format) }
    }
  }

  get("book/{isbn}") {
    val isbn = call.parameters["isbn"].orEmpty()
    val entry = backend.libraryEntry(isbn)
    if (entry == null) {
      call.notFoundPage("Book")
    } else {
      val attempts = backend.attemptsFor(isbn)
      call.page(entry.book.title, NavItem.LIBRARY) { bookDetailPage(entry, attempts, backend.serverInfo.format) }
    }
  }
  get("book/{isbn}/live") { call.bookLiveFragment(call.parameters["isbn"].orEmpty()) }

  post("book/{isbn}/download") {
    val isbn = call.parameters["isbn"].orEmpty()
    val params = call.params()
    val entry = backend.libraryEntry(isbn)
    val (message, kind) = describe(backend.enqueueDownload(isbn, params.format()), entry?.book?.title)
    call.toast(message, kind)
    if (params["view"] == "row") {
      val updated = backend.libraryEntry(isbn)
      if (updated == null) call.notFoundPage("Book") else call.fragment { bookRow(updated, selectable = true) }
    } else {
      call.bookLiveFragment(isbn)
    }
  }
  post("book/{isbn}/forget") {
    val isbn = call.parameters["isbn"].orEmpty()
    backend.forgetDownload(isbn)
    call.toast("Removed from download history.")
    call.bookLiveFragment(isbn)
  }

  // ---- queue ----
  suspend fun ApplicationCall.queueFragment() {
    val recent = backend.recentAttempts(30).filter { !it.status.isActive }.take(8)
    val active = backend.activeAttempts()
    fragment { queuePanel(active, recent) }
  }

  get("queue") {
    val recent = backend.recentAttempts(30).filter { !it.status.isActive }.take(8)
    val active = backend.activeAttempts()
    call.page("Queue", NavItem.QUEUE) { queuePage(active, recent, backend.serverInfo.format) }
  }
  get("queue/panel") { call.queueFragment() }
  post("queue/download-missing") {
    val queued = backend.downloadMissing()
    call.toast(if (queued == 0) "Nothing to download." else "Queued ${pluralize(queued, "book")} for download.")
    call.queueFragment()
  }
  post("queue/retry-failed") {
    val queued = backend.retryFailed()
    call.toast(if (queued == 0) "No failed downloads to retry." else "Retrying ${pluralize(queued, "book")}.")
    call.queueFragment()
  }
  post("queue/by-isbn") {
    val params = call.params()
    val isbn = parseIsbn(params["isbn"])
    if (isbn == null) {
      call.toast("That doesn't look like an ISBN. Use 10 or 13 digits.", "bad")
    } else {
      val (message, kind) = describe(backend.enqueueDownload(isbn, params.format()), isbn)
      call.toast(message, kind)
    }
    call.queueFragment()
  }

  // ---- history ----
  suspend fun historyData(params: Parameters): Triple<HistoryQuery, com.vishnurajeevan.libroabs.db.repo.AttemptPage, Map<AttemptStatus, Long>> {
    val query = HistoryQuery.parse(params)
    val counts = backend.attemptCounts()
    val page = if (query.active) {
      val active = backend.activeAttempts().map { it.attempt }.filter {
        query.text.isEmpty() || it.title.contains(query.text, true) || it.isbn.contains(query.text, true)
      }
      com.vishnurajeevan.libroabs.db.repo.AttemptPage(active, active.size.toLong())
    } else {
      backend.attemptPage(query.status, query.text, query.page, HistoryQuery.PAGE_SIZE)
    }
    return Triple(query, page, counts)
  }

  suspend fun ApplicationCall.historyFragment(params: Parameters) {
    val (query, page, counts) = historyData(params)
    fragment { historyResults(query, page, counts) }
  }

  // ---- attempts ----
  suspend fun ApplicationCall.renderView(view: String?, isbn: String?, params: Parameters) {
    when (view) {
      "row" -> {
        val entry = isbn?.let { backend.libraryEntry(it) }
        if (entry == null) notFoundPage("Book") else fragment { bookRow(entry, selectable = true) }
      }

      "history" -> historyFragment(params)
      "queue" -> queueFragment()
      else -> if (isbn != null) bookLiveFragment(isbn) else respond(HttpStatusCode.OK)
    }
  }

  post("attempts/{id}/cancel") {
    val id = call.parameters["id"]?.toLongOrNull()
    val params = call.params()
    val attempt = id?.let { backend.attempt(it) }
    if (attempt == null) {
      call.notFoundPage("Download")
    } else {
      val cancelled = backend.cancelAttempt(attempt.id)
      call.toast(if (cancelled) "Cancelled." else "That download has already finished.", if (cancelled) "ok" else "bad")
      // Cancellation is asynchronous; wait for the attempt to settle so the view reflects it.
      repeat(10) {
        if (backend.attempt(attempt.id)?.status?.isActive != true) return@repeat
        delay(50)
      }
      call.renderView(params["view"], attempt.isbn, params)
    }
  }
  post("attempts/{id}/retry") {
    val id = call.parameters["id"]?.toLongOrNull()
    val params = call.params()
    val attempt = id?.let { backend.attempt(it) }
    if (attempt == null) {
      call.notFoundPage("Download")
    } else {
      val (message, kind) = describe(backend.retryAttempt(attempt.id), attempt.title)
      call.toast(message, kind)
      call.renderView(params["view"], attempt.isbn, params)
    }
  }
  post("attempts/{id}/delete") {
    val id = call.parameters["id"]?.toLongOrNull()
    val params = call.params()
    val attempt = id?.let { backend.attempt(it) }
    if (attempt == null) {
      call.notFoundPage("Record")
    } else {
      if (backend.deleteAttempt(attempt.id)) call.toast("Record deleted.") else call.toast("Active downloads can't be deleted.", "bad")
      call.renderView(params["view"], attempt.isbn, params)
    }
  }

  get("history") {
    val (query, page, counts) = historyData(call.request.queryParameters)
    if (call.wantsFragment()) {
      call.fragment { historyResults(query, page, counts) }
    } else {
      call.response.headers.append(HttpHeaders.Vary, "HX-Request")
      call.page("History", NavItem.HISTORY) { historyPage(query, page, counts) }
    }
  }
  post("history/clear") {
    val params = call.params()
    val older = params["older"]
    val days = if (older == "all") null else older?.toIntOrNull() ?: 30
    val removed = backend.clearAttempts(days)
    call.toast(if (removed == 0) "Nothing to clear." else "Deleted ${pluralize(removed, "record")}.")
    call.historyFragment(params)
  }

  // ---- sync ----
  suspend fun ApplicationCall.syncFragment() {
    val sync = backend.syncSnapshot()
    val wishlist = backend.wishlist()
    fragment { syncPanel(sync, backend.serverInfo, wishlist) }
  }

  get("sync") {
    val sync = backend.syncSnapshot()
    val wishlist = backend.wishlist()
    call.page("Sync", NavItem.SYNC) { syncPage(sync, backend.serverInfo, wishlist) }
  }
  get("sync/panel") { call.syncFragment() }
  post("sync/run") {
    val params = call.params()
    val overwrite = params["overwrite"] == "true"
    if (backend.requestSync(overwrite)) {
      call.toast(if (overwrite) "Sync started — re-downloading everything." else "Sync started.")
    } else {
      call.toast("A sync is already running.", "bad")
    }
    if (params["view"] == "sync") call.syncFragment() else call.dashboardFragment()
  }

  // ---- settings ----
  get("settings") {
    call.page("Settings", NavItem.SETTINGS) { settingsPage(backend.serverInfo) }
  }
}
