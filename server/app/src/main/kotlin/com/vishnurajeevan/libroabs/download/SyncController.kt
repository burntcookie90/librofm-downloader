package com.vishnurajeevan.libroabs.download

import com.vishnurajeevan.libroabs.models.Logger
import com.vishnurajeevan.libroabs.models.graph.App
import com.vishnurajeevan.libroabs.models.server.SyncSnapshot
import com.vishnurajeevan.libroabs.models.server.SyncTrigger
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Tracks the state of full library syncs so the UI can show whether one is running and how the last one went.
 */
@Inject
@SingleIn(AppScope::class)
class SyncController(
  @App private val appScope: CoroutineScope,
  private val logger: Logger,
) {
  private val _state = MutableStateFlow(SyncSnapshot())
  val state: StateFlow<SyncSnapshot> = _state.asStateFlow()

  /** The sync implementation, registered by the app once it is ready. */
  @Volatile
  var runner: (suspend (trigger: SyncTrigger, overwrite: Boolean) -> Unit)? = null

  /** Runs [block] while recording its outcome. Failures are recorded and rethrown. */
  suspend fun <T> track(trigger: SyncTrigger, overwrite: Boolean, block: suspend () -> T): T {
    val start = System.currentTimeMillis()
    _state.update {
      it.copy(
        running = true,
        trigger = trigger,
        overwrite = overwrite,
        startedAt = it.startedAt ?: start,
        activeCount = it.activeCount + 1,
      )
    }
    var error: String? = null
    try {
      return block()
    } catch (e: CancellationException) {
      error = "Cancelled"
      throw e
    } catch (e: Throwable) {
      error = e.describe()
      throw e
    } finally {
      val end = System.currentTimeMillis()
      _state.update {
        val remaining = (it.activeCount - 1).coerceAtLeast(0)
        it.copy(
          running = remaining > 0,
          trigger = if (remaining > 0) it.trigger else null,
          startedAt = if (remaining > 0) it.startedAt else null,
          activeCount = remaining,
          lastFinishedAt = end,
          lastDurationMs = end - start,
          lastTrigger = trigger,
          lastError = error,
        )
      }
    }
  }

  /** Held for the whole of a sync, whatever started it, so syncs never overlap. */
  private val exclusive = Mutex()

  /** Starts a sync in the background. Returns `false` if one is already running or none is registered. */
  fun requestSync(trigger: SyncTrigger, overwrite: Boolean): Boolean {
    val run = runner ?: return false
    if (!exclusive.tryLock()) return false
    // Undispatched, so the running state is visible as soon as this returns.
    appScope.launch(start = CoroutineStart.UNDISPATCHED) {
      try {
        run(trigger, overwrite)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Throwable) {
        logger.i("Sync failed: ${e.describe()}")
      } finally {
        exclusive.unlock()
      }
    }
    return true
  }

  /** Runs [block] (a sync) unless one is already running, in which case it is skipped and `false` returned. */
  suspend fun runIfIdle(trigger: SyncTrigger, block: suspend () -> Unit): Boolean {
    if (!exclusive.tryLock()) {
      logger.i("Skipping ${trigger.name.lowercase()} sync: another sync is still running")
      return false
    }
    try {
      block()
    } finally {
      exclusive.unlock()
    }
    return true
  }
}

internal fun Throwable.describe(): String {
  val name = this::class.simpleName ?: "Error"
  val message = message?.trim()?.takeIf { it.isNotEmpty() }
  val head = if (message != null) "$name: $message" else name
  val cause = cause?.takeIf { it !== this }
  val full = if (cause != null) "$head (caused by ${cause.describe()})" else head
  return if (full.length > MAX_ERROR_LENGTH) full.take(MAX_ERROR_LENGTH) + "…" else full
}

private const val MAX_ERROR_LENGTH = 2000
