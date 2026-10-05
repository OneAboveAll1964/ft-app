package app.ft.carlife

import app.ft.core.ProtoWriter

class MediaStream(
    private val send: (serviceId: Int, payload: ByteArray) -> Unit,
    private val status: (module: Int, state: Int) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    @Volatile var oldVehicle = true
    @Volatile var open = false
        private set
    @Volatile private var resetWanted = false
    private var lastDataAt = 0L
    private var resetAt = Long.MIN_VALUE / 2
    var opened = 0
        private set

    fun data(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (resetWanted) applyReset()
        if (!open) {
            send(CarLifeProtocol.MEDIA_INIT, INIT)
            if (oldVehicle) {
                send(CarLifeProtocol.MEDIA_STOP, EMPTY)
                send(CarLifeProtocol.MEDIA_INIT, INIT)
            }
            status(CarLifeProtocol.MODULE_MUSIC, 1)
            open = true
            opened++
        }
        lastDataAt = clock()
        send(CarLifeProtocol.MEDIA_DATA, pcm)
    }

    fun idle(): Boolean {
        if (resetWanted) return applyReset()
        if (!open || clock() - lastDataAt < QUIET_MS) return false
        close()
        return true
    }

    fun launchedAgain() {
        val now = clock()
        val due = now - resetAt > RESET_GUARD_MS
        resetAt = now
        if (due) resetWanted = true
    }

    fun forget() {
        open = false
        resetWanted = false
        lastDataAt = 0L
        resetAt = Long.MIN_VALUE / 2
    }

    private fun applyReset(): Boolean {
        resetWanted = false
        if (!open) return false
        close()
        return true
    }

    private fun close() {
        send(CarLifeProtocol.MEDIA_PAUSE, EMPTY)
        status(CarLifeProtocol.MODULE_MUSIC, 0)
        open = false
    }

    companion object {
        const val QUIET_MS = 750L
        const val RESET_GUARD_MS = 5000L
        val INIT: ByteArray = ProtoWriter().int32(1, 48000).int32(2, 2).int32(3, 16).toByteArray()
        private val EMPTY = ByteArray(0)
    }
}
