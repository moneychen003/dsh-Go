package io.github.ccch1mneyyy.dsh_app.phone.background

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** One disposable display, one gesture. Only a matching DOWN/UP receipt proves delivery. */
internal class InputProbe(private val displayId: Int) {
    class Failure(val code: String) : Exception(code)
    data class Target(val displayId: Int, val x: Int, val y: Int)

    private val ready = CountDownLatch(1)
    private val received = CountDownLatch(1)
    @Volatile private var target: Target? = null
    @Volatile private var failure: Throwable? = null
    @Volatile private var skipped = false
    private var downTime: Long? = null
    private var down = false

    fun ready(target: Target) {
        this.target = target
        ready.countDown()
    }

    fun fail(error: Throwable) {
        failure = error
        ready.countDown()
    }

    /**
     * Some ROMs refuse to attach the probe window to a virtual display (for example ColorOS on
     * Android 16 raises SecurityException from Presentation.show()). That refusal says nothing
     * about whether later background injection will work, so mark the probe inconclusive instead
     * of failing the gate: ready + skipped means "self-check unavailable", not "input denied".
     */
    fun skip() {
        skipped = true
        ready.countDown()
    }

    fun verify(
        now: () -> Long,
        send: (Target, Long) -> Unit,
        readyTimeoutMs: Long = 3000,
        receiptTimeoutMs: Long = 1000,
    ) {
        if (!ready.await(readyTimeoutMs, TimeUnit.MILLISECONDS)) throw Failure("inputWindowNotReady")
        if (skipped) return
        failure?.let { throw it }
        val point = target ?: throw Failure("inputWindowNotReady")
        if (displayId <= 0 || point.displayId != displayId || point.x <= 0 || point.y <= 0)
            throw Failure("inputWindowNotReady")
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val time = now()
        synchronized(this) { downTime = time }
        send(point, time) // Async injection, never replayed; its boolean is not a receipt.
        if (!received.await(receiptTimeoutMs, TimeUnit.MILLISECONDS)) throw Failure("inputUnconfirmed")
    }

    @Synchronized fun accept(id: Int, time: Long, action: Int, x: Float, y: Float) {
        val point = target ?: return
        if (id != displayId || downTime != time || !x.isFinite() || !y.isFinite() ||
            abs(x - point.x) > 2 || abs(y - point.y) > 2) return
        when (action) {
            0 -> down = true // ACTION_DOWN
            1 -> if (down) { down = false; received.countDown() } // ACTION_UP
            3 -> down = false // ACTION_CANCEL
        }
    }
}
