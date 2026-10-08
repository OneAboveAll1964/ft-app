package app.ft.carlife

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.InetAddress

class HotspotClients(
    private val scope: CoroutineScope,
    private val addresses: () -> List<Pair<Inet4Address, Int>>,
    private val wanted: () -> Boolean,
    private val onChange: (List<String>?) -> Unit
) {
    private var job: Job? = null
    private var last: List<String>? = null
    private var reported = false

    fun start() {
        if (job?.isActive == true) return
        last = null
        reported = false
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                if (wanted()) {
                    val own = runCatching { addresses() }.getOrDefault(emptyList())
                    val found = if (own.isEmpty()) null else runCatching { sweep(own) }.getOrDefault(emptyList())
                    if (!reported || found != last) {
                        reported = true
                        last = found
                        onChange(found)
                    }
                }
                delay(EVERY_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        last = null
        reported = false
    }

    private suspend fun sweep(own: List<Pair<Inet4Address, Int>>): List<String> = coroutineScope {
        val hosts = own.flatMap { (ip, prefix) -> hostsOf(ip, prefix) }.distinct()
        hosts.chunked(PARALLEL).flatMap { chunk ->
            chunk.map { h -> async { if (runCatching { InetAddress.getByName(h).isReachable(TIMEOUT_MS) }.getOrDefault(false)) h else null } }.awaitAll().filterNotNull()
        }
    }

    companion object {
        const val EVERY_MS = 12_000L
        private const val PARALLEL = 48
        private const val TIMEOUT_MS = 400

        fun hostsOf(own: Inet4Address, prefix: Int): List<String> {
            if (prefix !in 1..30) return emptyList()
            val bits = prefix.coerceAtLeast(24)
            val b = own.address
            val v = ((b[0].toInt() and 0xff) shl 24) or ((b[1].toInt() and 0xff) shl 16) or ((b[2].toInt() and 0xff) shl 8) or (b[3].toInt() and 0xff)
            val mask = if (bits == 32) -1 else (-1 shl (32 - bits))
            val net = v and mask
            val size = 1 shl (32 - bits)
            return (1 until size - 1).map { net or it }.filter { it != v }.map { x ->
                "${(x ushr 24) and 0xff}.${(x ushr 16) and 0xff}.${(x ushr 8) and 0xff}.${x and 0xff}"
            }
        }
    }
}
