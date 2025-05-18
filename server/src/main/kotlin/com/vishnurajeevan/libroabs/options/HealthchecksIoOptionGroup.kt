package com.vishnurajeevan.libroabs.options

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

class HealthchecksIoOptionGroup : OptionGroup() {
  val healthCheckHost by option("--healthcheck-host", envvar = "HEALTHCHECK_HOST")
    .default("https://hc-ping.com")

  val healthCheckId by option("--healthcheck-id", envvar = "HEALTHCHECK_ID")
    .required()

}