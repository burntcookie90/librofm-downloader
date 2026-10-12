package com.vishnurajeevan.libroabs.server.ui

/**
 * Locks a client out after repeated wrong passwords, whether they were typed into the login form or sent as an API key.
 * While locked out even the right password is refused, so guessing can't continue in parallel.
 */
class LoginThrottle(
  private val maxFailures: Int = 5,
  private val windowMillis: Long = 15 * 60 * 1000L,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private val failures = HashMap<String, ArrayDeque<Long>>()

  @Synchronized
  fun isLocked(client: String): Boolean = recentFailures(client).size >= maxFailures

  /** Runs [check] unless [client] is locked out, recording the result. Returns false when locked out. */
  @Synchronized
  fun attempt(client: String, check: () -> Boolean): Boolean {
    if (isLocked(client)) return false
    if (check()) {
      failures.remove(client)
      return true
    }
    if (failures.size >= MAX_TRACKED_CLIENTS) failures.keys.toList().forEach { recentFailures(it) }
    failures.getOrPut(client) { ArrayDeque() }.addLast(clock())
    return false
  }

  private fun recentFailures(client: String): ArrayDeque<Long> {
    val times = failures[client] ?: return ArrayDeque()
    val cutoff = clock() - windowMillis
    while (times.isNotEmpty() && times.first() <= cutoff) times.removeFirst()
    if (times.isEmpty()) failures.remove(client)
    return times
  }

  private companion object {
    const val MAX_TRACKED_CLIENTS = 10_000
  }
}
