package com.armsx2.config

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Coalescing debounce for the EXPENSIVE tail of a settings change.
 *
 * ## What this exists for
 *
 * `InGameOverlay.saveSettings` used to run, synchronously on the UI thread, on every single
 * settings edit — and the D-pad auto-repeat fires one every 110 ms
 * (`MainActivityRuntime.NAV_REPEAT_INTERVAL_MS`). Measured on an SM-A127M (TASK-0084):
 *
 * | variant                | UI-thread cost per edit |
 * |------------------------|-------------------------|
 * | Global scope, no VM    | 14,6 ms (median)        |
 * | Game scope, no VM      | 26,3 ms                 |
 * | Game scope, VM running | **145,8 ms**            |
 *
 * All three blow the 16,7 ms frame budget; the last one by 9×. Of those 145,8 ms, ~125 are the
 * per-game INI regeneration plus [Settings.applyTo] — and inside `applyTo` the dominant term is
 * the final `NativeApp.commitSettings()`, which is
 * `Host::RunOnCPUThread(…, block = true)` on the native side: it BLOCKS until the CPU thread
 * drains its queue at the vsync boundary, so in a game running at 12 fps one call waits ~83 ms.
 * The cost therefore scales with how SLOW the emulated game is, which is exactly why the report
 * came from a low-end device.
 *
 * ## What is deferred and what is not
 *
 * Only the native tail is deferred: the per-game INI export and the live apply. **Persistence is
 * NOT** — `ConfigStore.save` still runs synchronously inside `saveSettings`. That is deliberate
 * and it is the difference between this and the "send ConfigStore.save to the queue" shape the
 * bug report sketched:
 *
 *  - deferring the store opens a data-loss window (process killed before the flush), and
 *  - it opens a stale-read window for every `ConfigStore` reader in the app,
 *
 * while buying only ~10 ms of the ~146. Not worth either hole. What is left pending here is the
 * INI and the native state, both of which are re-derived from the (already saved) store, so the
 * worst case of losing a pending job is a stale `gamesettings/<serial>_<CRC>.ini` — which
 * [flush] closes at `onPause`, and which the next edit regenerates anyway.
 *
 * ## Coalescing
 *
 * Only the LAST job survives: sweeping a slider with the D-pad queues one job per repeat and runs
 * one at the end. [DEBOUNCE_MS] is the quiet period after the last edit; [MAX_DEFER_MS] caps how
 * long a sustained hold can starve the apply, so the emulator still reacts while a key is held
 * down rather than only when it is released.
 *
 * Everything runs on the MAIN thread. That is not laziness: `NativeApp.setSetting` writes into
 * `s_settings_interface`, a `MemorySettingsInterface` with no mutex, and other UI-thread callers
 * (BIOS pick, pad setup, memory-card assign) write into it too. Running the apply on a background
 * thread would make those a data race. Moving it off-thread needs native-side locking first; see
 * TASK-0084 for the measured number that is still on the table because of it.
 */
object SettingsApplyQueue {
    /** Quiet period after the last edit before the pending job runs. Comfortably longer than the
     *  110 ms D-pad repeat, so a held direction coalesces into a single apply. */
    private const val DEBOUNCE_MS = 150L

    /** Hard cap on how long a job may sit pending while edits keep arriving. Without it, holding
     *  a direction would postpone the live apply indefinitely and the emulator would look frozen
     *  on the old value. */
    private const val MAX_DEFER_MS = 600L

    private val handler = Handler(Looper.getMainLooper())

    /** The pending work, or null when nothing is queued. Replaced wholesale on each schedule —
     *  that IS the coalescing. Only ever touched on the main thread. */
    private var pending: (() -> Unit)? = null

    /** `elapsedRealtime` of the first edit of the current pending burst, for [MAX_DEFER_MS]. */
    private var burstStartedAt = 0L

    /** How many edits the pending job has absorbed, for the log line. */
    private var coalesced = 0

    private val runner = Runnable { runPending() }

    /**
     * Queue [job], replacing whatever was pending.
     *
     * Must be called from the main thread (every caller is a Compose event handler). [job] itself
     * will run on the main thread too.
     */
    fun schedule(job: () -> Unit) {
        val now = SystemClock.elapsedRealtime()
        if (pending == null) {
            burstStartedAt = now
            coalesced = 0
        }
        pending = job
        coalesced++
        handler.removeCallbacks(runner)
        // Never push the apply past MAX_DEFER_MS from the start of the burst.
        val remaining = (burstStartedAt + MAX_DEFER_MS) - now
        handler.postDelayed(runner, DEBOUNCE_MS.coerceAtMost(remaining.coerceAtLeast(0L)))
    }

    /**
     * Run the pending job NOW, if there is one.
     *
     * Called wherever the deferred state must be real before something else happens: leaving the
     * pause menu, `Activity.onPause` (so a swipe-kill can't strand a stale per-game INI), and
     * before any other writer of the same native/INI state runs (`SettingsViewModel.reset*`).
     *
     * A no-op off the main thread — the job touches native settings state that only the main
     * thread may write. Callers that could be on another thread should post instead.
     */
    fun flush() {
        if (Looper.myLooper() != Looper.getMainLooper()) return
        handler.removeCallbacks(runner)
        runPending()
    }

    /** True when an apply is still owed. Only meaningful on the main thread. */
    fun hasPending(): Boolean = pending != null

    private fun runPending() {
        val job = pending ?: return
        val n = coalesced
        pending = null
        coalesced = 0
        val t0 = SystemClock.elapsedRealtimeNanos()
        // runCatching, not try/finally-with-rethrow: this runs from a Handler callback, so an
        // escaping exception would kill the process. The old inline code was already wrapped in
        // runCatching at the call site (InGameOverlay.saveSettings), so swallowing here keeps the
        // same failure behaviour rather than inventing a new one.
        runCatching { job() }
            .onFailure { println("@@ANDROID_SETTINGS_APPLY@@ error=${it.javaClass.simpleName}") }
        println(
            "@@ANDROID_SETTINGS_APPLY@@ ms=%.2f coalesced=%d".format(
                (SystemClock.elapsedRealtimeNanos() - t0) / 1e6, n,
            ),
        )
    }
}
