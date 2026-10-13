package com.vishnurajeevan.libroabs.server.ui

import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.figcaption
import kotlinx.html.figure
import kotlinx.html.form
import kotlinx.html.h2
import kotlinx.html.id
import kotlinx.html.img
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.span
import kotlinx.html.strong
import kotlinx.html.summary
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul

/** The book page's Audiobookshelf card. Nothing is compared until asked, since it means calling Audiobookshelf. */
fun FlowContent.absBookCard(isbn: String) {
  div("card") {
    div("page-head") {
      h2 { +"Audiobookshelf" }
    }
    div {
      id = "abs-sync"
      p("muted small") { +"See how Audiobookshelf's details for this book differ from libro.fm's, then choose whether to update them." }
      compareButton("/ui/book/$isbn/abs", "#abs-sync", "Compare with Audiobookshelf")
    }
  }
}

private fun FlowContent.compareButton(url: String, target: String, label: String) {
  button(classes = "btn small") {
    attributes["type"] = "button"
    attributes["hx-get"] = url
    attributes["hx-target"] = target
    attributes["hx-swap"] = "innerHTML"
    attributes["hx-indicator"] = "this"
    +label
    span("htmx-indicator") { +" …" }
  }
}

/** One book's comparison, with the button that applies it. */
fun FlowContent.absPlanView(isbn: String, result: AbsPlanResult) {
  when (result) {
    is AbsPlanResult.Failure -> {
      div("alert bad") { +"Couldn't compare with Audiobookshelf: ${result.message}" }
      compareButton("/ui/book/$isbn/abs", "#abs-sync", "Try again")
    }
    AbsPlanResult.NotInAudiobookshelf -> {
      p("muted") { +"Audiobookshelf doesn't have this book. Books are matched by ISBN, by the folder this app saved them to, or by title and author." }
      compareButton("/ui/book/$isbn/abs", "#abs-sync", "Check again")
    }
    is AbsPlanResult.Found -> {
      val plan = result.plan
      p("muted small") {
        +"Matched "
        span("mono") { +plan.itemPath }
        +" by ${plan.matchedBy}."
      }
      form {
        id = "abs-apply"
        if (plan.changes.isEmpty()) {
          p { +"Audiobookshelf already has libro.fm's details for this book." }
        } else {
          p("muted small") { +"Untick anything you'd rather keep as it is in Audiobookshelf." }
          changesTable(plan.changes, selectable = true)
        }
        coverComparison(plan)
        input(type = InputType.hidden, name = "itemId") { value = plan.itemId }
        label("check") {
          input(type = InputType.checkBox, name = "cover") {
            value = "on"
            checked = plan.changes.isNotEmpty() || !plan.hasCover
          }
          span { +" Replace the cover with libro.fm's" }
        }
        div("row-actions") {
          button(classes = "btn primary small") {
            attributes["type"] = "button"
            attributes["hx-post"] = "/ui/book/$isbn/abs/apply"
            attributes["hx-include"] = "#abs-apply"
            attributes["hx-target"] = "#abs-sync"
            attributes["hx-swap"] = "innerHTML"
            attributes["hx-indicator"] = "this"
            +if (plan.changes.isEmpty()) "Apply cover" else "Apply selected changes"
            span("htmx-indicator") { +" …" }
          }
          compareButton("/ui/book/$isbn/abs", "#abs-sync", "Compare again")
        }
      }
    }
  }
}

private fun FlowContent.changesTable(changes: List<MetadataChange>, selectable: Boolean = false) {
  table("diff") {
    thead {
      tr {
        if (selectable) th(classes = "pick") { span("sr-only") { +"Apply" } }
        th { +"Field" }
        th { +"Audiobookshelf now" }
        th { +"From libro.fm" }
      }
    }
    tbody {
      changes.forEach { change ->
        tr {
          if (selectable) {
            td("pick") {
              input(type = InputType.checkBox, name = "field") {
                value = change.key
                checked = true
                attributes["aria-label"] = "Apply ${change.field}"
              }
            }
          }
          th { attributes["scope"] = "row"; +change.field }
          td("before") { +change.before }
          td("after") { +change.after }
        }
      }
    }
  }
}

private fun FlowContent.coverComparison(plan: AbsSyncPlan) {
  if (plan.coverUrl == null) return
  div("cover-compare") {
    figure {
      if (plan.hasCover) {
        img(src = "/ui/audiobookshelf/cover/${plan.itemId}", alt = "Audiobookshelf's cover", classes = "cover") {
          attributes["loading"] = "lazy"
        }
      } else {
        div("cover")
      }
      figcaption("muted small") { +if (plan.hasCover) "Audiobookshelf now" else "No cover in Audiobookshelf" }
    }
    figure {
      img(src = plan.coverUrl, alt = "libro.fm's cover", classes = "cover") {
        attributes["loading"] = "lazy"
        attributes["referrerpolicy"] = "no-referrer"
      }
      figcaption("muted small") { +"libro.fm" }
    }
  }
}

/** The library-wide comparison page; the comparison itself loads once the page is up. */
fun FlowContent.absLibraryPage() {
  pageHead(
    "Audiobookshelf metadata",
    "Compare every book in your libro.fm library with Audiobookshelf, then choose which to update. Nothing changes until you apply.",
  )
  div("card") {
    id = "abs-overview"
    attributes["hx-get"] = "/ui/audiobookshelf/compare"
    attributes["hx-trigger"] = "load"
    attributes["hx-swap"] = "innerHTML"
    p("muted") { +"Comparing your library with Audiobookshelf…" }
  }
}

fun FlowContent.absOverview(overview: AbsSyncOverview, fields: List<Pair<String, String>>) {
  div("stats") {
    stat(overview.plans.size, "differ", if (overview.plans.isNotEmpty()) "busy" else "")
    stat(overview.upToDate, "up to date", "")
    stat(overview.notInAudiobookshelf.size, "not in Audiobookshelf", "")
    if (overview.errors.isNotEmpty()) stat(overview.errors.size, "couldn't compare", "bad")
  }
  if (overview.errors.isNotEmpty()) {
    details {
      summary("err-text") { +"${pluralize(overview.errors.size, "book")} couldn't be compared" }
      ul { overview.errors.forEach { li("err-text") { +it } } }
    }
  }
  if (overview.plans.isEmpty()) {
    emptyState("Nothing to update", "Every book Audiobookshelf has already matches libro.fm.")
  } else {
    form {
      id = "abs-bulk"
      div("bulkbar") {
        label("check") {
          input(type = InputType.checkBox) {
            checked = true
            attributes["aria-label"] = "Select all books"
            attributes["onclick"] =
              "document.querySelectorAll('#abs-bulk input[name=item]').forEach(function(c){c.checked=this.checked}.bind(this))"
          }
          span("muted small") { +"${pluralize(overview.plans.size, "book")} with differences" }
        }
        label("check") {
          input(type = InputType.checkBox, name = "cover") { value = "on"; checked = true }
          span("small") { +" Replace covers" }
        }
        details("field-picks") {
          summary("small") { +"Fields to update" }
          div("chips") {
            val changed = overview.plans.flatMap { plan -> plan.changes.map { it.key } }.toSet()
            fields.filter { (key, _) -> key in changed }.forEach { (key, label) ->
              label("check") {
                input(type = InputType.checkBox, name = "field") { value = key; checked = true }
                span("small") { +label }
              }
            }
          }
        }
        button(classes = "btn primary small") {
          attributes["type"] = "button"
          attributes["hx-post"] = "/ui/audiobookshelf/apply"
          attributes["hx-include"] = "#abs-bulk"
          attributes["hx-target"] = "#abs-overview"
          attributes["hx-swap"] = "innerHTML"
          attributes["hx-indicator"] = "this"
          attributes["hx-confirm"] = "Update the selected books in Audiobookshelf with libro.fm's details?"
          +"Apply to selected"
          span("htmx-indicator") { +" …" }
        }
      }
      div("rows") {
        overview.plans.forEach { plan ->
          div("row") {
            div("top") {
              label("pick") {
                input(type = InputType.checkBox, name = "item") {
                  value = "${plan.isbn}|${plan.itemId}"
                  checked = true
                  attributes["aria-label"] = "Update ${plan.title}"
                }
              }
              a("/ui/book/${plan.isbn}", classes = "name") { +plan.title }
            }
            div("facts") {
              span("mono") { +plan.itemPath }
              span { +"matched by ${plan.matchedBy}" }
            }
            details {
              summary("small") { +plan.changes.joinToString(", ") { it.field } }
              changesTable(plan.changes)
            }
          }
        }
      }
    }
  }
  if (overview.notInAudiobookshelf.isNotEmpty()) {
    details {
      summary("muted small") { +"${pluralize(overview.notInAudiobookshelf.size, "book")} Audiobookshelf doesn't have" }
      ul { overview.notInAudiobookshelf.forEach { li("small") { +it } } }
    }
  }
}

private fun FlowContent.stat(n: Int, label: String, kind: String) {
  div("stat $kind".trim()) {
    span("n") { +n.toString() }
    span("l") { +label }
  }
}

/** What happened when the selected books were updated. */
fun FlowContent.absApplyResults(applied: Int, failures: List<Pair<String, String>>) {
  if (applied > 0) p { strong { +"Updated ${pluralize(applied, "book")} in Audiobookshelf." } }
  if (failures.isNotEmpty()) {
    div("alert bad") {
      +"${pluralize(failures.size, "book")} couldn't be updated:"
      ul { failures.forEach { (title, error) -> li { +"$title: $error" } } }
    }
  }
  compareButton("/ui/audiobookshelf/compare", "#abs-overview", "Compare again")
}
