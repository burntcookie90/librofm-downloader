package com.vishnurajeevan.libroabs.models

import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.redactForLog
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

interface Logger {
  fun v(msg: String)

  fun i(msg: String)
}

@Inject
@ContributesBinding(AppScope::class)
class RealLogger(
  private val serverInfo: ServerInfo,
) : Logger {
  private val applicationLogLevel: ApplicationLogLevel get() = serverInfo.logLevel

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
    println("${Clock.System.now()}: ${serverInfo.redactForLog(msg)}")
  }
}