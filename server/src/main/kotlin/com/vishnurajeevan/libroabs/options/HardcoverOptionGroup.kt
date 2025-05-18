package com.vishnurajeevan.libroabs.options

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

class HardcoverOptionGroup : OptionGroup() {
  val hardcoverToken by option("--hardcover", envvar = "HARDCOVER_TOKEN")
    .required()

  val hardcoverEndpoint by option("--hardcover-endpoint", envvar = "HARDCOVER_ENDPOINT")
    .default("https://api.hardcover.app/v1/graphql")
}