package com.vishnurajeevan.libroabs.models.server

private val urlQuery = Regex("""(https?://[^\s?#"']+)\?[^\s"']*""")

/** The part of a webhook URL that is safe to show; the path often carries the webhook's secret. */
fun webhookHost(url: String): String = url.substringAfter("://").substringBefore('/')

/**
 * Removes anything from a log line that grants access: configured webhook URLs, the health check ID, and URL query
 * strings (Libro.fm download links are pre-signed).
 */
fun ServerInfo.redactForLog(message: String): String {
  var redacted = message
  webhookUrls.filter { it.isNotEmpty() }.forEach { redacted = redacted.replace(it, "${it.substringBefore("://")}://${webhookHost(it)}/…") }
  healthCheckId?.takeIf { it.isNotEmpty() }?.let { redacted = redacted.replace(it, "…") }
  audiobookshelf?.apiKey?.takeIf { it.isNotEmpty() }?.let { redacted = redacted.replace(it, "…") }
  return redacted.replace(urlQuery, "$1?…")
}
