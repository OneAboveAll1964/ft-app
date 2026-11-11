package app.ft.aa

import app.ft.core.NavigationInfo
import app.ft.core.ProtoReader

class AaNavigation {
    private var info = NavigationInfo()
    private var modern = false
    private var ended = false

    fun reset(): NavigationInfo {
        info = NavigationInfo()
        modern = false
        ended = false
        return info
    }

    fun accept(id: Int, payload: ByteArray): NavigationInfo? {
        val r = ProtoReader(payload)
        when (id) {
            AaProtocol.NAV_STATUS -> when (r.int(1, -1)) {
                0, 2 -> { info = NavigationInfo(); ended = true }
                1 -> { ended = false; info = info.copy(active = true) }
                3 -> { ended = false; info = NavigationInfo(active = true) }
                else -> return null
            }
            AaProtocol.NAV_STATE -> {
                if (ended) return null
                val step = r.messages(1).firstOrNull() ?: return null
                val maneuver = step.message(1) ?: return null
                val type = maneuver.int(1, -1)
                if (type < 0) return null
                modern = true
                val road = step.message(2)?.string(1)
                    ?: step.message(4)?.string(1) ?: ""
                setTurn(type, road, maneuver.int(2).coerceAtLeast(0))
            }
            AaProtocol.NAV_CURRENT_POSITION -> {
                if (ended) return null
                val step = r.message(1) ?: return null
                val distance = step.message(1) ?: return null
                if (!distance.has(1) || distance.int(1) < 0) return null
                modern = true
                info = info.copy(active = true, distanceMeters = distance.int(1),
                    timeSeconds = step.long(2).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            }
            AaProtocol.NAV_NEXT_TURN -> {
                if (modern || ended || !r.has(3)) return null
                val left = r.int(2) == 1
                val type = when (r.int(3, -1)) {
                    0 -> 0; 1 -> 1; 2 -> 2
                    3 -> if (left) 5 else 6
                    4 -> if (left) 7 else 8
                    5 -> if (left) 9 else 10
                    6 -> if (left) 11 else 12
                    7 -> if (left) 15 else 16
                    8 -> if (left) 23 else 24
                    9 -> if (left) 25 else 26
                    10 -> if (left) 27 else 28
                    11 -> 30; 12 -> 31; 13 -> if (left) 34 else 32
                    14 -> 36; 16 -> 37; 17 -> 38; 18, 19 -> 39
                    else -> return null
                }
                setTurn(type, r.string(1) ?: "", r.int(5).coerceAtLeast(0))
            }
            AaProtocol.NAV_DISTANCE -> {
                if (modern || ended || !r.has(1) || r.int(1) < 0) return null
                info = info.copy(active = true, distanceMeters = r.int(1), timeSeconds = r.int(2).coerceAtLeast(0))
            }
            else -> return null
        }
        return info
    }

    private fun setTurn(type: Int, road: String, exit: Int) {
        val changed = type != info.maneuver || road != info.road || exit != info.exit
        info = info.copy(active = true, maneuver = type, road = road, exit = exit,
            distanceMeters = if (changed) null else info.distanceMeters,
            timeSeconds = if (changed) 0 else info.timeSeconds)
    }
}
