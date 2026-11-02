package app.ft.aa

import app.ft.core.Pcm16Diagnostics

/** Serializes the two PCM producers and rejects callbacks from a previous voice request. */
internal class AaMicrophoneInput(
    private val send: (ByteArray) -> Unit,
    private val selectCar: () -> Unit,
    private val log: (String) -> Unit
) {
    private var generation = 0L
    private var wanted = false
    private var carSelected = false
    private var phonePackets = 0L
    private var carPackets = 0L
    private var phoneBytes = 0L
    private var carBytes = 0L
    private val diagnostics = Pcm16Diagnostics({ log("microphone $it") })

    @get:Synchronized val phoneAllowed: Boolean get() = wanted && !carSelected

    @Synchronized fun open(): Long {
        if (!wanted) {
            generation++
            wanted = true
            carSelected = false
            phonePackets = 0
            carPackets = 0
            phoneBytes = 0
            carBytes = 0
            diagnostics.reset()
        }
        return generation
    }

    @Synchronized fun phone(token: Long, pcm: ByteArray) {
        if (!wanted || token != generation || carSelected || !valid(pcm)) return
        phonePackets++
        phoneBytes += pcm.size
        forward(pcm)
    }

    @Synchronized fun car(pcm: ByteArray) {
        if (!wanted || !valid(pcm)) return
        if (!carSelected) {
            carSelected = true
            log("using the car's microphone for Android Auto; phone PCM disabled")
            selectCar()
        }
        carPackets++
        carBytes += pcm.size
        forward(pcm)
    }

    private fun valid(pcm: ByteArray) = pcm.isNotEmpty() && pcm.size % 2 == 0

    private fun forward(pcm: ByteArray) {
        diagnostics.record(pcm)
        send(pcm)
    }

    @Synchronized fun close() {
        if (!wanted) return
        wanted = false
        val timing = diagnostics.finish()
        log("microphone send attempts: phonePackets=$phonePackets phoneBytes=$phoneBytes carPackets=$carPackets carBytes=$carBytes peak=${diagnostics.peak} $timing")
    }
}
