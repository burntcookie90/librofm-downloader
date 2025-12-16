package com.vishnurajeevan.libroabs.models

import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

interface Logger {
  fun v(msg: String)

  fun i(msg: String)
}

@Inject
@ContributesBinding(AppScope::class)
class RealLogger(
  private val applicationLogLevel: ApplicationLogLevel,
) : Logger {
  override fun v(msg: String) {
    when (applicationLogLevel) {
      ApplicationLogLevel.VERBOSE -> log(msg)
      else -> {}
    }
  }

  override fun i(msg: String) {
    when (applicationLogLevel) {
      ApplicationLogLevel.INFO, ApplicationLogLevel.VERBOSE -> log(msg)
      else -> {}
    }
  }

  @OptIn(ExperimentalTime::class)
  private fun log(msg: String) {
    println("${Clock.System.now()}: $msg")
  }
}