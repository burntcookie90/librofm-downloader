package com.vishnurajeevan.libroabs.server.ui

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebUiRoutesTest {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private fun backend(password: String? = null, bookCount: Int = 40) =
    FakeBackend(testServerInfo(password), scope, bookCount = bookCount, stepDelayMs = 30)

  private fun ui(backend: FakeBackend, block: suspend ApplicationTestBuilder.() -> Unit) = try {
    testApplication {
      application { installWebUi(backend) }
      block()
    }
  } finally {
    scope.cancel()
  }

  private fun ApplicationTestBuilder.browser(): HttpClient = createClient {
    followRedirects = false
    install(HttpCookies)
  }

  private suspend fun HttpClient.htmxGet(url: String, vararg extra: Pair<String, String>) = get(url) {
    header("HX-Request", "true")
    extra.forEach { (k, v) -> header(k, v) }
  }

  private suspend fun HttpClient.htmxPost(url: String, form: Map<String, List<String>> = emptyMap()): HttpResponse = post(url) {
    header("HX-Request", "true")
    contentType(ContentType.Application.FormUrlEncoded)
    setBody(form.entries.joinToString("&") { (k, vs) -> vs.joinToString("&") { "$k=${java.net.URLEncoder.encode(it, "UTF-8")}" } })
  }

  @Test
  fun dashboardRenders() = ui(backend()) {
    val body = browser().get("/ui").also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText()
    assertContains(body, "<title>Dashboard · libro.fm Downloader</title>")
    assertContains(body, "reader@example.com")
    assertContains(body, "/ui/static/htmx.min.js")
    assertContains(body, "viewport")
    // all primary destinations are reachable from the nav
    listOf("/ui/library", "/ui/queue", "/ui/history", "/ui/sync", "/ui/settings").forEach { assertContains(body, "href=\"$it\"") }
  }

  @Test
  fun everyPageRenders() = ui(backend()) {
    val client = browser()
    listOf("/ui", "/ui/library", "/ui/queue", "/ui/history", "/ui/sync", "/ui/settings", "/ui/login").forEach {
      val r = client.get(it)
      assertTrue(r.status == HttpStatusCode.OK || r.status == HttpStatusCode.Found, "$it -> ${r.status}")
    }
    listOf("/ui/status", "/ui/dashboard/panel", "/ui/queue/panel", "/ui/sync/panel").forEach {
      assertEquals(HttpStatusCode.OK, client.htmxGet(it).status, it)
    }
  }

  @Test
  fun staticAssetsAreServed() = ui(backend()) {
    val client = browser()
    val css = client.get("/ui/static/app.css")
    assertEquals(HttpStatusCode.OK, css.status)
    assertContains(css.contentType().toString(), "css")
    assertEquals(HttpStatusCode.OK, client.get("/ui/static/htmx.min.js").status)
    assertEquals(HttpStatusCode.OK, client.get("/ui/static/app.js").status)
  }

  @Test
  fun settingsNeverShowSecrets() = ui(backend("pw-secret")) {
    val client = browser()
    val login = client.submitForm("/ui/login", parameters { append("password", "pw-secret") })
    assertEquals(HttpStatusCode.Found, login.status)
    val body = client.get("/ui/settings").bodyAsText()
    assertFalse(body.contains("hunter2"))
    assertFalse(body.contains("secret-token"))
    assertFalse(body.contains("pw-secret"))
    assertFalse(body.contains("hooks.example.com/secret"), "webhook paths can contain secrets")
    assertContains(body, "hooks.example.com")
    assertContains(body, "reader@example.com")
  }

  // ---- library ----

  @Test
  fun libraryReturnsFragmentForHtmxAndFullPageOtherwise() = ui(backend()) {
    val client = browser()
    val full = client.get("/ui/library").bodyAsText()
    assertContains(full, "<html")
    assertContains(full, "id=\"library-results\"")

    val fragment = client.htmxGet("/ui/library?q=Hollow")
    val text = fragment.bodyAsText()
    assertFalse(text.contains("<html"))
    assertContains(text, "Hollow Crown")
    assertFalse(text.contains("Night Library"))
    assertContains(fragment.headers[HttpHeaders.Vary].orEmpty(), "HX-Request")

    // htmx restoring the page from its history cache needs the whole document
    val restored = client.htmxGet("/ui/library?q=Hollow", "HX-History-Restore-Request" to "true").bodyAsText()
    assertContains(restored, "<html")
  }

  @Test
  fun libraryFiltersAndPaginates() = ui(backend()) {
    val client = browser()
    val page1 = client.htmxGet("/ui/library").bodyAsText()
    assertContains(page1, "Page 1 of 2")
    assertContains(page1, "Next →")
    assertFalse(page1.contains("Previous"))
    val page2 = client.htmxGet("/ui/library?page=2").bodyAsText()
    assertContains(page2, "← Previous")

    val missing = client.htmxGet("/ui/library?filter=MISSING&page=1").bodyAsText()
    assertFalse(missing.contains("data-state=\"DOWNLOADED\""))
    assertContains(missing, "data-state=\"NOT_DOWNLOADED\"")
    assertContains(client.htmxGet("/ui/library?q=zzzzzz").bodyAsText(), "No books match")
  }

  @Test
  fun userTextIsEscaped() = ui(backend()) {
    val client = browser()
    val body = client.get("/ui/library?q=" + java.net.URLEncoder.encode("<script>alert(1)</script>", "UTF-8")).bodyAsText()
    assertFalse(body.contains("<script>alert(1)"))
    assertContains(body, "&lt;script&gt;")
  }

  // ---- downloads ----

  @Test
  fun mutationsRequireHtmxHeader() = ui(backend()) {
    val client = browser()
    val isbn = "9780000000001"
    val plain = client.post("/ui/book/$isbn/download?view=row")
    assertEquals(HttpStatusCode.Forbidden, plain.status)
  }

  @Test
  fun manualDownloadQueuesAndRowPolls() = ui(backend()) {
    val b = backend()
    val client = browser()
    val book = b.books.first { b.libraryEntry(it.isbn)?.state == BookState.NOT_DOWNLOADED }
    val response = client.htmxPost("/ui/book/${book.isbn}/download?view=row")
    assertEquals(HttpStatusCode.OK, response.status)
    assertNotNull(response.headers["HX-Trigger"])
    assertContains(response.headers["HX-Trigger"]!!, "queued")
    val html = response.bodyAsText()
    assertContains(html, "id=\"book-${book.isbn}\"")
    assertContains(html, "hx-trigger=\"every 2s\"")
    assertContains(html, "Cancel")
  }

  @Test
  fun downloadEndpointQueuesBook() {
    val b = backend()
    ui(b) {
      val client = browser()
      val book = b.books.first { b.libraryEntry(it.isbn)?.state == BookState.NOT_DOWNLOADED }
      client.htmxPost("/ui/book/${book.isbn}/download?view=row")
      assertEquals(listOf(book.isbn), b.enqueued.toList())
      // second click while active doesn't double-queue
      val again = client.htmxPost("/ui/book/${book.isbn}/download?view=row")
      assertContains(again.headers["HX-Trigger"]!!, "already")
      assertEquals(1, b.enqueued.size)
    }
  }

  @Test
  fun formatOverrideIsPassedThrough() {
    val b = backend()
    ui(b) {
      val client = browser()
      val book = b.books.first()
      client.htmxPost("/ui/book/${book.isbn}/download?view=detail", mapOf("format" to listOf("MP3")))
      delay(10)
      val attempt = b.attempts.last { it.isbn == book.isbn }
      assertEquals(com.vishnurajeevan.libroabs.models.server.BookFormat.MP3, attempt.requestedFormat)
      // unknown formats fall back to the default
      client.htmxPost("/ui/book/${b.books[1].isbn}/download?view=detail", mapOf("format" to listOf("WAV")))
      assertEquals(null, b.attempts.last { it.isbn == b.books[1].isbn }.requestedFormat)
    }
  }

  @Test
  fun bulkDownloadQueuesSelectedAndRerendersList() {
    val b = backend()
    ui(b) {
      val client = browser()
      val isbns = b.books.takeLast(3).map { it.isbn }
      val response = client.htmxPost("/ui/library/download", mapOf("isbn" to isbns, "filter" to listOf("MISSING"), "page" to listOf("1")))
      assertContains(response.headers["HX-Trigger"]!!, "Queued 3 books")
      assertEquals(isbns.toSet(), b.enqueued.toSet())
      assertContains(response.bodyAsText(), "id=\"library-page\"")
    }
  }

  @Test
  fun bulkDownloadWithNothingSelectedExplains() = ui(backend()) {
    val response = browser().htmxPost("/ui/library/download")
    assertContains(response.headers["HX-Trigger"]!!, "Select at least one")
    assertContains(response.headers["HX-Trigger"]!!, "bad")
  }

  @Test
  fun downloadMissingQueuesEverythingNotDownloaded() {
    val b = backend()
    ui(b) {
      val client = browser()
      val response = client.htmxPost("/ui/queue/download-missing")
      assertEquals(HttpStatusCode.OK, response.status)
      // 40 books: 20 downloaded, 3 failed, 17 untouched => 20 queued
      assertEquals(20, b.enqueued.size)
      assertContains(response.bodyAsText(), "id=\"queue-panel\"")
    }
  }

  @Test
  fun nonAsciiTitlesProduceAsciiHeaders() {
    val b = backend()
    b.books[0] = sampleBook(1, "Café Ünïcode ☕ “quoted”")
    ui(b) {
      val response = browser().htmxPost("/ui/book/${b.books[0].isbn}/download?view=row")
      val header = response.headers["HX-Trigger"]!!
      assertTrue(header.all { it.code in 0x20..0x7e }, header)
      assertContains(response.bodyAsText(), "Café Ünïcode")
    }
  }

  @Test
  fun downloadByIsbnValidatesInput() {
    val b = backend()
    ui(b) {
      val client = browser()
      val bad = client.htmxPost("/ui/queue/by-isbn", mapOf("isbn" to listOf("not-an-isbn")))
      assertContains(bad.headers["HX-Trigger"]!!, "doesn't look like an ISBN")
      assertEquals(0, b.enqueued.size)

      val good = client.htmxPost("/ui/queue/by-isbn", mapOf("isbn" to listOf("978-0-0000-0000-5")))
      assertContains(good.headers["HX-Trigger"]!!, "queued")
      assertEquals(listOf("9780000000005"), b.enqueued.toList())

      val unknown = client.htmxPost("/ui/queue/by-isbn", mapOf("isbn" to listOf("9789999999999")))
      assertContains(unknown.headers["HX-Trigger"]!!, "Could not find")
    }
  }

  @Test
  fun cancelStopsAnActiveDownload() {
    val b = FakeBackend(testServerInfo(), scope, stepDelayMs = 2_000)
    ui(b) {
      val client = browser()
      val book = b.books.first { b.libraryEntry(it.isbn)?.state == BookState.NOT_DOWNLOADED }
      client.htmxPost("/ui/book/${book.isbn}/download?view=row")
      val id = b.attempts.last().id
      val response = client.htmxPost("/ui/attempts/$id/cancel?view=row")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(com.vishnurajeevan.libroabs.models.server.AttemptStatus.CANCELLED, b.attempt(id)!!.status)
      assertContains(response.bodyAsText(), "data-state=\"NOT_DOWNLOADED\"")
    }
  }

  @Test
  fun retryFailedAttemptFromHistory() {
    val b = backend()
    ui(b) {
      val client = browser()
      val failed = b.attempts.first { it.status == com.vishnurajeevan.libroabs.models.server.AttemptStatus.FAILED }
      val response = client.htmxPost("/ui/attempts/${failed.id}/retry?view=history", mapOf("status" to listOf("FAILED")))
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(listOf(failed.isbn), b.enqueued.toList())
      assertEquals(com.vishnurajeevan.libroabs.models.server.AttemptTrigger.RETRY, b.attempts.last().trigger)
      assertContains(response.bodyAsText(), "id=\"history-status\"")
    }
  }

  @Test
  fun unknownAttemptsAndBooksReturn404() = ui(backend()) {
    val client = browser()
    assertEquals(HttpStatusCode.NotFound, client.get("/ui/book/0000000000000").status)
    assertEquals(HttpStatusCode.NotFound, client.htmxPost("/ui/attempts/99999/retry").status)
    assertEquals(HttpStatusCode.NotFound, client.htmxPost("/ui/attempts/abc/cancel").status)
    assertEquals(HttpStatusCode.NotFound, client.htmxGet("/ui/library/row/0000000000000").status)
  }

  // ---- book page ----

  @Test
  fun bookPageShowsDetailsAndAttempts() {
    val b = backend()
    ui(b) {
      val downloaded = b.books.first()
      val html = browser().get("/ui/book/${downloaded.isbn}").bodyAsText()
      assertContains(html, downloaded.title)
      assertContains(html, "Narrator")
      assertContains(html, "Saved to")
      assertContains(html, "Forget download")
      assertContains(html, "A description of book 1.")
      assertFalse(html.contains("<p>A description"), "description html is stripped")
      assertContains(html, "Download again")
      assertContains(html, "Default (M4B, MP3 fallback)")
    }
  }

  @Test
  fun forgetRemovesDownloadState() {
    val b = backend()
    ui(b) {
      val book = b.books.first()
      assertEquals(BookState.DOWNLOADED, b.libraryEntry(book.isbn)!!.state)
      browser().htmxPost("/ui/book/${book.isbn}/forget")
      assertEquals(BookState.NOT_DOWNLOADED, b.libraryEntry(book.isbn)!!.state)
    }
  }

  // ---- history ----

  @Test
  fun historyFiltersByStatusAndSearch() = ui(backend()) {
    val client = browser()
    val failed = client.htmxGet("/ui/history?status=FAILED").bodyAsText()
    assertContains(failed, "Connection reset")
    assertFalse(failed.contains("Succeeded</span>"))
    val searched = client.htmxGet("/ui/history?q=zzz").bodyAsText()
    assertContains(searched, "Nothing matches")
    val active = client.htmxGet("/ui/history?status=ACTIVE").bodyAsText()
    assertContains(active, "Nothing matches")
    assertContains(client.get("/ui/history").bodyAsText(), "Every download attempt")
  }

  @Test
  fun historyClearRemovesFinishedOnly() {
    val b = backend()
    ui(b) {
      val client = browser()
      val book = b.books.last()
      client.htmxPost("/ui/book/${book.isbn}/download?view=row")
      val response = client.htmxPost("/ui/history/clear", mapOf("older" to listOf("all")))
      assertContains(response.headers["HX-Trigger"]!!, "Deleted")
      assertTrue(b.attempts.all { it.status.isActive })
      assertEquals(1, b.attempts.size)
    }
  }

  @Test
  fun deleteSingleAttempt() {
    val b = backend()
    ui(b) {
      val first = b.attempts.first()
      browser().htmxPost("/ui/attempts/${first.id}/delete?view=history")
      assertTrue(b.attempts.none { it.id == first.id })
    }
  }

  // ---- sync ----

  @Test
  fun syncRunStartsOnceAndReportsRunning() {
    val b = backend()
    ui(b) {
      val client = browser()
      val first = client.htmxPost("/ui/sync/run?view=sync", mapOf("overwrite" to listOf("true")))
      assertContains(first.headers["HX-Trigger"]!!, "Sync started")
      assertContains(first.bodyAsText(), "id=\"sync-panel\"")
      assertTrue(b.sync.running)
      assertTrue(b.sync.overwrite)
      val second = client.htmxPost("/ui/sync/run?view=dashboard")
      assertContains(second.headers["HX-Trigger"]!!, "already running")
      assertContains(second.bodyAsText(), "id=\"dashboard-panel\"")
    }
  }

  // ---- auth ----

  @Test
  fun unauthenticatedRequestsAreRedirectedToLogin() = ui(backend("pw")) {
    val client = browser()
    val response = client.get("/ui/library")
    assertEquals(HttpStatusCode.Found, response.status)
    assertEquals("/ui/login?next=%2Fui%2Flibrary", response.headers[HttpHeaders.Location])

    val htmx = client.htmxGet("/ui/status")
    assertEquals(HttpStatusCode.Unauthorized, htmx.status)
    assertEquals("/ui/login", htmx.headers["HX-Redirect"])

    assertEquals(HttpStatusCode.OK, client.get("/ui/login").status)
    assertEquals(HttpStatusCode.OK, client.get("/ui/static/app.css").status)
  }

  @Test
  fun wrongPasswordIsRejected() = ui(backend("pw")) {
    val client = browser()
    val response = client.submitForm("/ui/login", parameters { append("password", "nope") })
    assertEquals(HttpStatusCode.Unauthorized, response.status)
    assertContains(response.bodyAsText(), "Incorrect password")
    assertEquals(HttpStatusCode.Found, client.get("/ui").status)
  }

  @Test
  fun loginGrantsAccessAndLogoutRevokesIt() = ui(backend("pw")) {
    val client = browser()
    val login = client.submitForm("/ui/login", parameters {
      append("password", "pw")
      append("next", "/ui/queue")
    })
    assertEquals(HttpStatusCode.Found, login.status)
    assertEquals("/ui/queue", login.headers[HttpHeaders.Location])
    val cookie = login.headers[HttpHeaders.SetCookie].orEmpty()
    assertContains(cookie, "HttpOnly")
    assertContains(cookie, "SameSite=Strict")

    assertEquals(HttpStatusCode.OK, client.get("/ui/queue").status)
    assertContains(client.get("/ui").bodyAsText(), "Log out")

    client.htmxPost("/ui/logout")
    assertEquals(HttpStatusCode.Found, client.get("/ui/queue").status)
  }

  @Test
  fun sessionCookieIsSecureBehindAnHttpsProxy() = ui(backend("pw")) {
    val direct = browser().submitForm("/ui/login", parameters { append("password", "pw") })
    assertFalse(direct.headers[HttpHeaders.SetCookie].orEmpty().contains("Secure"))
    val proxied = browser().submitForm("/ui/login", parameters { append("password", "pw") }) { header("X-Forwarded-Proto", "https") }
    assertContains(proxied.headers[HttpHeaders.SetCookie].orEmpty(), "Secure")
  }

  @Test
  fun loginIgnoresOpenRedirects() = ui(backend("pw")) {
    val login = browser().submitForm("/ui/login", parameters {
      append("password", "pw")
      append("next", "https://evil.example/")
    })
    assertEquals("/ui", login.headers[HttpHeaders.Location])
  }

  @Test
  fun apiKeyAndBearerTokenAuthorize() = ui(backend("pw")) {
    val client = browser()
    assertEquals(HttpStatusCode.OK, client.get("/ui/queue") { header(HttpHeaders.Authorization, "Bearer pw") }.status)
    assertEquals(HttpStatusCode.OK, client.get("/ui/queue") { header("X-Api-Key", "pw") }.status)
    assertEquals(HttpStatusCode.Found, client.get("/ui/queue") { header(HttpHeaders.Authorization, "Bearer wrong") }.status)
  }

  @Test
  fun repeatedWrongApiKeysLockOutPasswordsButNotSessions() = ui(backend("pw")) {
    val client = browser()
    assertEquals(HttpStatusCode.Found, client.submitForm("/ui/login", parameters { append("password", "pw") }).status)
    val api = browser()
    repeat(5) { api.get("/ui/queue") { header("X-Api-Key", "guess-$it") } }
    assertEquals(HttpStatusCode.Found, api.get("/ui/queue") { header("X-Api-Key", "pw") }.status)
    assertEquals(HttpStatusCode.TooManyRequests, api.submitForm("/ui/login", parameters { append("password", "pw") }).status)
    // an existing session keeps working
    assertEquals(HttpStatusCode.OK, client.get("/ui/queue").status)
  }

  @Test
  fun apiRefusesRequestsMadeByOtherSites() = try {
    testApplication {
      application {
        installWebUi(backend())
        routing { get("/update") { call.respondText("updating") } }
      }
      val client = browser()
      assertEquals(HttpStatusCode.OK, client.get("/update").status, "scripts send no fetch metadata")
      assertEquals(HttpStatusCode.OK, client.get("/update") { header("Sec-Fetch-Site", "none") }.status, "typed into the address bar")
      assertEquals(HttpStatusCode.OK, client.get("/update") { header("Sec-Fetch-Site", "same-origin") }.status)
      assertEquals(HttpStatusCode.Forbidden, client.get("/update") { header("Sec-Fetch-Site", "cross-site") }.status)
      assertEquals(HttpStatusCode.Forbidden, client.get("/update") { header("Sec-Fetch-Site", "same-site") }.status)
      assertEquals(HttpStatusCode.Forbidden, client.get("/update") { header(HttpHeaders.Origin, "https://evil.example") }.status)
      assertEquals(HttpStatusCode.OK, client.get("/update") { header(HttpHeaders.Origin, "http://localhost") }.status)
      // links from other sites into the UI still work
      assertEquals(HttpStatusCode.OK, client.get("/ui") { header("Sec-Fetch-Site", "cross-site") }.status)
    }
  } finally {
    scope.cancel()
  }

  @Test
  fun otherSitesCannotPostToTheLoginForm() = ui(backend("pw")) {
    val response = browser().submitForm("/ui/login", parameters { append("password", "pw") }) { header("Sec-Fetch-Site", "cross-site") }
    assertEquals(HttpStatusCode.Forbidden, response.status)
  }

  @Test
  fun forgedSessionCookieIsRejected() = ui(backend("pw")) {
    val client = browser()
    val response = client.get("/ui/queue") { header(HttpHeaders.Cookie, "libro_session=99999999999.AAAA") }
    assertEquals(HttpStatusCode.Found, response.status)
  }

  @Test
  fun withoutPasswordTheUiIsOpenButStillCsrfProtected() = ui(backend()) {
    val client = browser()
    assertEquals(HttpStatusCode.OK, client.get("/ui/queue").status)
    assertEquals(HttpStatusCode.Forbidden, client.post("/ui/queue/download-missing").status)
    assertFalse(client.get("/ui").bodyAsText().contains("Log out"))
  }
}
