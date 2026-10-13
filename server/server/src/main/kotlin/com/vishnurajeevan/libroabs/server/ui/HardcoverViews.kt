package com.vishnurajeevan.libroabs.server.ui

import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.summary
import kotlinx.html.ul

/** The Hardcover preview page; the comparison loads once the page is up, since it takes a while. */
fun FlowContent.hardcoverPage() {
  pageHead(
    "Hardcover",
    "What syncing would change on Hardcover. Untick anything you'd rather leave, then apply. Nothing changes until you do.",
  )
  div("card") {
    id = "hardcover-preview"
    attributes["hx-get"] = "/ui/hardcover/preview"
    attributes["hx-trigger"] = "load"
    attributes["hx-swap"] = "innerHTML"
    p("muted") { +"Comparing with Hardcover… this can take a minute for a large library, as Hardcover limits how fast it can be asked." }
  }
}

fun FlowContent.hardcoverOverview(overview: HardcoverOverview) {
  div("stats") {
    stat(overview.books.size, "to update", if (overview.books.isNotEmpty()) "busy" else "")
    stat(overview.upToDate, "up to date", "")
    stat(overview.notFound.size, "not on Hardcover", "")
    if (overview.errors.isNotEmpty()) stat(overview.errors.size, "couldn't compare", "bad")
  }
  if (overview.errors.isNotEmpty()) {
    details {
      summary("err-text") { +"${pluralize(overview.errors.size, "problem")} while comparing" }
      ul { overview.errors.forEach { li("err-text") { +it } } }
    }
  }
  when {
    !overview.progressCompared -> p("muted small") { +"Set ABS_URL and ABS_API_KEY to also send listening progress from Audiobookshelf." }
    !overview.progressSyncedAutomatically -> p("muted small") {
      +"Listening progress is only sent from here. Set HARDCOVER_SYNC_PROGRESS=true to send it on every sync."
    }
  }
  if (overview.books.isEmpty()) {
    emptyState("Nothing to update", "Hardcover already matches libro.fm and your listening.")
  } else {
    form {
      id = "hardcover-apply"
      input(type = InputType.hidden, name = "planId") { value = overview.planId }
      div("bulkbar") {
        label("check") {
          input(type = InputType.checkBox) {
            checked = true
            attributes["aria-label"] = "Select everything"
            attributes["onclick"] =
              "document.querySelectorAll('#hardcover-apply input[name=action]').forEach(function(c){c.checked=this.checked}.bind(this))"
          }
          span("muted small") { +"${pluralize(overview.books.size, "book")} to update" }
        }
        button(classes = "btn primary small") {
          attributes["type"] = "button"
          attributes["hx-post"] = "/ui/hardcover/apply"
          attributes["hx-include"] = "#hardcover-apply"
          attributes["hx-target"] = "#hardcover-preview"
          attributes["hx-swap"] = "innerHTML"
          attributes["hx-indicator"] = "this"
          attributes["hx-confirm"] = "Make the selected changes on Hardcover?"
          +"Apply selected"
          span("htmx-indicator") { +" …" }
        }
      }
      div("rows") {
        overview.books.forEach { book -> hardcoverBookRow(book) }
      }
    }
  }
  if (overview.notFound.isNotEmpty()) {
    details {
      summary("muted small") { +"${pluralize(overview.notFound.size, "book")} Hardcover doesn't seem to have" }
      p("muted small") { +"Nothing on Hardcover had the same ISBN, or a close enough title by the same author." }
      ul { overview.notFound.forEach { li("small") { +it } } }
    }
  }
}

private fun FlowContent.hardcoverBookRow(book: HardcoverBookPlan) {
  div("row") {
    div("top") {
      a("/ui/book/${book.isbn}", classes = "name") { +book.title }
      book.author?.let { span("muted small") { +it } }
    }
    book.match?.let { match ->
      div("facts") {
        if (match.createsEdition) {
          span {
            +"Adds this audiobook edition to "
            if (match.url != null) {
              a(match.url) {
                attributes["target"] = "_blank"
                attributes["rel"] = "noopener noreferrer"
                +match.title
              }
            } else {
              +match.title
            }
            +" on Hardcover"
          }
          span { +"matched by ${match.matchedBy}" }
        } else {
          span { +"Audiobook edition on Hardcover (matched by ${match.matchedBy})" }
        }
      }
    }
    div {
      book.actions.forEach { action ->
        label("check") {
          input(type = InputType.checkBox, name = "action") {
            value = action.key
            checked = action.selected
          }
          span { +" ${action.description}" }
        }
        action.note?.let { p("muted small") { +it } }
      }
    }
  }
}

fun FlowContent.hardcoverApplyResults(result: HardcoverApplyResult, dryRun: Boolean) {
  when {
    result.expired -> p { +"That comparison is out of date. Compare again before applying." }
    dryRun -> p { +"Dry run: nothing was changed. The changes that would have been made are in the log." }
    result.applied > 0 -> p { strong { +"Updated ${pluralize(result.applied, "book")} on Hardcover." } }
  }
  if (result.failures.isNotEmpty()) {
    div("alert bad") {
      +"${pluralize(result.failures.size, "book")} couldn't be updated:"
      ul { result.failures.forEach { (title, error) -> li { +"$title: $error" } } }
    }
  }
  button(classes = "btn small") {
    attributes["type"] = "button"
    attributes["hx-get"] = "/ui/hardcover/preview"
    attributes["hx-target"] = "#hardcover-preview"
    attributes["hx-swap"] = "innerHTML"
    attributes["hx-indicator"] = "this"
    +"Compare again"
    span("htmx-indicator") { +" …" }
  }
}
