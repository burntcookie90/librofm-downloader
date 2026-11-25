package com.vishnurajeevan.libroabs.options

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class HardcoverOptionGroup : OptionGroup() {
  val hardcoverToken by option("--hardcover", envvar = "HARDCOVER_TOKEN")
    .required()

  val hardcoverEndpoint by option("--hardcover-endpoint", envvar = "HARDCOVER_ENDPOINT")
    .default("https://api.hardcover.app/v1/graphql")

  val hardcoverSyncMode by option("--hardcover-sync-mode", envvar = "HARDCOVER_SYNC_MODE")
    .enum<TrackerSyncMode>(ignoreCase = true)
    .default(TrackerSyncMode.LIBRO_OWNED_TO_HARDCOVER)

  val hardcoverSyncProgress by option("--hardcover-sync-progress", envvar = "HARDCOVER_SYNC_PROGRESS")
    .flag(default = false)

  /**
   * How often Hardcover is synced on its own, between library syncs: `30m`, `1h`, ... A sync that changes nothing
   * takes a handful of requests, well inside Hardcover's 60 a minute and 5,000 a day. `0` syncs it only with the library.
   */
  val hardcoverSyncInterval by option("--hardcover-sync-interval", envvar = "HARDCOVER_SYNC_INTERVAL")
    .convert { raw ->
      val interval = if (raw.trim() == "0") Duration.ZERO else Duration.parseOrNull(raw.trim()) ?: fail("expected a duration such as 30m or 1h, or 0")
      if (interval != Duration.ZERO && interval < MIN_INTERVAL) fail("must be at least $MIN_INTERVAL, or 0")
      interval
    }
    .default(30.minutes)

  private companion object {
    val MIN_INTERVAL = 5.minutes
  }
}
