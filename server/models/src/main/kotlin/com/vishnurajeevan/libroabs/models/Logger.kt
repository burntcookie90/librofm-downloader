package com.vishnurajeevan.libroabs.models

import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import me.tatarka.inject.annotations.Inject
import software.amazon.lastmile.kotlin.inject.anvil.AppScope
import software.amazon.lastmile.kotlin.inject.anvil.ContributesBinding

fun interface Logger {
  fun log(msg: String)
}

@Inject
@ContributesBinding(AppScope::class)
class RealLogger(
  private val applicationLogLevel: ApplicationLogLevel
) : Logger {
  override fun log(msg: String) {
    when (applicationLogLevel) {
      ApplicationLogLevel.INFO, ApplicationLogLevel.VERBOSE -> println(msg)
      else -> {}
    }
  }
}