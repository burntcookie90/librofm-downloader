package com.vishnurajeevan.libroabs.server.ui

import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.DownloadAttempt
import kotlinx.html.DL
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.p
import kotlinx.html.span

fun FlowContent.bookDetailPage(
  entry: LibraryEntry,
  attempts: List<DownloadAttempt>,
  defaultFormat: BookFormat,
  audiobookshelf: Boolean = false,
) {
  val book = entry.book
  div {
    a("/ui/library", classes = "muted small") { +"← Library" }
  }
  div("detail-head") {
    bookCover(book)
    div {
      h1 { +book.title }
      if (book.authors.isNotEmpty()) p("muted") { +book.authors.joinToString(", ") }
      p("muted small") { +bookMeta(book) }
    }
  }
  bookLive(entry, attempts, defaultFormat)
  if (audiobookshelf) absBookCard(book.isbn)
  div("grid two") {
    div("card") {
      h2 { +"Details" }
      dl("kv") {
        detail("ISBN", book.isbn, mono = true)
        if (book.audiobook_info.narrators.isNotEmpty()) detail("Narrated by", book.audiobook_info.narrators.joinToString(", "))
        book.series?.let { detail("Series", it + (book.series_num?.let { n -> " #$n" } ?: "")) }
        detail("Publisher", book.publisher)
        detail("Published", book.publication_date.isoDate())
        detail("Length", formatDuration(book.audiobook_info.duration))
        detail("Tracks", book.audiobook_info.track_count.toString())
      }
      if (book.genres.isNotEmpty()) {
        div("chips") { book.genres.forEach { badge(it.name) } }
      }
    }
    div("card") {
      h2 { +"About" }
      if (book.description.isBlank()) {
        p("muted") { +"No description." }
      } else {
        div("desc") { +stripHtml(book.description) }
      }
    }
  }
}

private fun DL.detail(key: String, value: String, mono: Boolean = false) {
  if (value.isBlank()) return
  dt { +key }
  dd(if (mono) "mono" else null) { +value }
}

/** libro.fm descriptions contain light HTML; show them as plain text. */
fun stripHtml(html: String): String = html
  .replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
  .replace(Regex("<[^>]+>"), "")
  .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
  .replace("&#39;", "'").replace("&nbsp;", " ")
  .trim()

/** The part of the book page that changes as downloads progress; polls while a download is active. */
fun FlowContent.bookLive(entry: LibraryEntry, attempts: List<DownloadAttempt>, defaultFormat: BookFormat) {
  val isbn = entry.book.isbn
  div("grid") {
    id = "book-live"
    if (entry.state.isActive) {
      attributes["hx-get"] = "/ui/book/$isbn/live"
      attributes["hx-trigger"] = "every 2s"
      attributes["hx-swap"] = "outerHTML"
    }
    div("card") {
      div("page-head") {
        h2 { +"Download" }
        stateBadge(entry)
      }
      val download = entry.download
      if (download != null) {
        dl("kv") {
          detail("Format", formatLabel(download.format.name))
          detail("Saved to", download.path, mono = true)
          detail("Extras (PDF)", if (download.hasPdfDownloaded) "Downloaded" else "—")
        }
      }
      entry.step?.let { p("muted") { +"$it…" } }
      val latest = entry.latestAttempt
      if (entry.state == BookState.FAILED && latest?.error != null) {
        div("err-text") { +"Last attempt failed: ${latest.error}" }
      }
      if (entry.state.isActive) {
        latest?.let { actionButton("Cancel download", "/ui/attempts/${it.id}/cancel?view=detail", "#book-live", "btn danger") }
      } else {
        manualDownloadForm(entry, defaultFormat)
        if (download != null) {
          div("row-actions") {
            actionButton(
              "Forget download", "/ui/book/$isbn/forget", "#book-live",
              classes = "btn small danger",
              confirm = "Remove this book from the download history? Files are kept, but the next sync will download it again."
            )
          }
        }
      }
    }
    div("card") {
      h2 { +"Attempts" }
      if (attempts.isEmpty()) {
        p("muted") { +"This book has not been downloaded by this app yet." }
      } else {
        div("rows") {
          attempts.forEach { attemptRow(it, showTitle = false, view = "detail") }
        }
      }
    }
  }
}
