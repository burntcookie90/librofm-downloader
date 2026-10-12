package com.vishnurajeevan.libroabs

import com.vishnurajeevan.libroabs.models.server.ApplicationLogLevel
import com.vishnurajeevan.libroabs.models.server.BookFormat
import com.vishnurajeevan.libroabs.models.server.ServerInfo
import com.vishnurajeevan.libroabs.models.server.TrackerSyncMode
import com.vishnurajeevan.libroabs.models.server.redactForLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRedactionTest {
  private val info = ServerInfo(
    libroUserName = "u", libroPassword = "libro-pass", port = 0, dataDir = "", mediaDir = "", syncInterval = "d", parallelCount = 1,
    dryRun = false, renameChapters = false, writeTitleTag = false, format = BookFormat.MP3, downloadExtras = false,
    logLevel = ApplicationLogLevel.VERBOSE, limit = -1, pathPattern = "", healthCheckHost = "https://hc-ping.com",
    healthCheckId = "5f1c-hc-id", trackerToken = "tracker-token", trackerEndpoint = "", ffmpegPath = "", ffprobePath = "",
    audioQuality = "128k", skipTrackingIsbns = emptyList(), hardcoverSyncMode = TrackerSyncMode.ALL,
    webhookUrls = listOf("https://discord.com/api/webhooks/123/hook-secret"),
    libroFmHeaders = mapOf("X-Custom-Auth" to "header-secret"),
    webUiPassword = "ui-pass",
  )

  @Test
  fun redactsWebhooksHealthCheckIdsAndSignedUrls() {
    assertEquals("REQUEST: https://discord.com/…", info.redactForLog("REQUEST: https://discord.com/api/webhooks/123/hook-secret"))
    assertEquals("ping https://hc-ping.com/…/start", info.redactForLog("ping https://hc-ping.com/5f1c-hc-id/start"))
    assertEquals(
      "Downloading https://cdn.example/book.zip?… to part-0.zip",
      info.redactForLog("Downloading https://cdn.example/book.zip?X-Amz-Signature=abc&X-Amz-Credential=def to part-0.zip"),
    )
  }

  @Test
  fun startupSummaryHasNoSecrets() {
    val summary = info.prettyPrint()
    listOf("libro-pass", "5f1c-hc-id", "tracker-token", "hook-secret", "/api/webhooks", "header-secret", "ui-pass").forEach {
      assertFalse(summary.contains(it), "summary leaks $it")
    }
    assertTrue(summary.contains("discord.com"))
    assertTrue(summary.contains("X-Custom-Auth"))
  }
}
