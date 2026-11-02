package app.ft.carlife

import app.ft.core.NavigationInfo
import app.ft.core.ProtoWriter
import java.util.concurrent.CountDownLatch

/** Serializes cluster and speech ownership of CarLife's shared NAVI module. */
class NavigationRelay(
    private val send: (action: Int, info: NavigationInfo, totalDistance: Int) -> Unit,
    private val module: (Boolean) -> Unit,
    private val dispatch: (() -> Unit) -> Unit,
    private var enabled: Boolean = true,
    private val onError: (Exception) -> Unit = {}
) {
    private val lock = Any()
    private val pending = ArrayDeque<() -> Unit>()
    private val voiceWaiters = HashSet<CountDownLatch>()
    private var draining = false
    private var latest = NavigationInfo()
    private var subscribed = false
    private var voice = false
    private var shown: NavigationInfo? = null
    private var total = 0
    @Volatile var active = false
        private set

    fun update(info: NavigationInfo) = change { latest = info; publish() }
    fun subscribe(on: Boolean) = change { subscribed = on; publish() }
    fun enable(on: Boolean) = change { enabled = on; publish() }
    fun voice(on: Boolean) = change { voice = on; updateModule() }
    /** Only the dedicated voice writer waits here; AA and shutdown never wait for I/O. */
    fun voiceAndWait(on: Boolean) {
        val done = CountDownLatch(1)
        try {
            change {
                voice = on
                updateModule()
                voiceWaiters += done
                pending.addLast {
                    synchronized(lock) { voiceWaiters -= done }
                    done.countDown()
                }
            }
            done.await()
        } finally {
            synchronized(lock) { voiceWaiters -= done }
        }
    }

    fun reset() = synchronized(lock) {
        pending.clear()
        voiceWaiters.forEach { it.countDown() }
        voiceWaiters.clear()
        // Keep the current consumer until its in-flight write actually returns.
        latest = NavigationInfo(); subscribed = false; voice = false
        shown = null; total = 0; active = false
    }

    private fun change(block: () -> Unit) {
        val start = synchronized(lock) {
            block()
            if (!draining && pending.isNotEmpty()) { draining = true; true } else false
        }
        if (start) {
            try { dispatch(::drain) } catch (e: Exception) {
                synchronized(lock) { draining = false }
                throw e
            }
        }
    }

    private fun drain() {
        while (true) {
            val effect = synchronized(lock) {
                if (pending.isEmpty()) { draining = false; return }
                pending.removeFirst()
            }
            // Transport writes can block. Never hold the state lock while doing I/O.
            try { effect() } catch (e: Exception) {
                // A failed packet must not strand the queue or prevent shutdown/reset.
                runCatching { onError(e) }
            }
        }
    }

    private fun updateModule() {
        val wanted = voice || (enabled && subscribed && latest.active)
        if (wanted != active) { active = wanted; pending.addLast { module(wanted) } }
    }

    private fun publish() {
        // Activate NAVI before the first maneuver; clear the maneuver before deactivating it.
        if (enabled && subscribed && latest.active) updateModule()
        val previous = shown
        if (!enabled || !subscribed || !latest.active || latest.maneuver == 0) {
            if (previous != null) pending.addLast { send(3, NavigationInfo(maneuver = 39), 0) }
            shown = null
            total = 0
        } else if (latest.distanceMeters != null && latest != previous) {
            val changed = previous == null || previous.maneuver != latest.maneuver ||
                previous.road != latest.road || previous.exit != latest.exit
            if (changed) total = latest.distanceMeters ?: 0
            val action = if (previous == null) 1 else 2
            val info = latest
            val distance = total
            pending.addLast { send(action, info, distance) }
            shown = latest
        }
        updateModule()
    }

    companion object {
        fun turnCode(maneuver: Int): Int = when (maneuver) {
            1 -> 23
            3, 5, 13, 21, 25, 27 -> 8
            4, 6, 14, 22, 26, 28 -> 2
            7, 15, 23 -> 7
            8, 16, 24 -> 3
            9, 17 -> 6
            10, 18 -> 4
            11, 12, 19, 20 -> 5
            30, 32, 33, 34, 35, 43, 45 -> 9
            31, 44, 46 -> 10
            37, 47, 48 -> 29
            38, 49, 50 -> 30
            39, 40, 41, 42 -> 24
            else -> 1
        }

        fun payload(action: Int, info: NavigationInfo, totalDistance: Int, icon: ByteArray): ByteArray =
            ProtoWriter().int32(1, action).int32(2, turnCode(info.maneuver))
                .string(3, info.road).int32(4, totalDistance.coerceAtLeast(0))
                .int32(5, (info.distanceMeters ?: 0).coerceAtLeast(0))
                // Older CarLife expects seconds here; newer versions expect a PNG. Unknown
                // wire types are skipped by protobuf, so each receiver reads its own field 6.
                .int32(6, info.timeSeconds.coerceAtLeast(0)).bytes(6, icon).toByteArray()
    }
}
