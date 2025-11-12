package com.vishnurajeevan.libroabs.options

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.enum
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode

class HardcoverOptionGroup : OptionGroup() {
  val hardcoverToken by option("--hardcover", envvar = "HARDCOVER_TOKEN")
    .required()

  val hardcoverEndpoint by option("--hardcover-endpoint", envvar = "HARDCOVER_ENDPOINT")
    .default("https://api.hardcover.app/v1/graphql")

  val hardcoverSyncMode by option("--hardcover-sync-mode", envvar = "HARDCOVER_SYNC_MODE")
    .enum<TrackerSyncMode>(ignoreCase = true)
    .default(TrackerSyncMode.LIBRO_OWNED_TO_HARDCOVER)
}

