package app.ft.aa

import app.ft.core.CarProtocol
import app.ft.core.VehicleLocation

internal class AaDrivingStatus {
    private var statusFromSpeed = UNRESTRICTED

    fun update(protocol: CarProtocol, vehicle: VehicleLocation?, nowMs: Long, restrictionsEnabled: Boolean): Int {
        statusFromSpeed = fromSpeed(protocol, vehicle, nowMs) ?: statusFromSpeed
        return if (restrictionsEnabled) statusFromSpeed else UNRESTRICTED
    }

    private fun fromSpeed(protocol: CarProtocol, vehicle: VehicleLocation?, nowMs: Long): Int? {
        val source = when (protocol) {
            CarProtocol.CARLIFE -> "BAIDU"
            CarProtocol.ICCOA -> "ICCOA"
        }
        if (vehicle?.source != source) return null
        val speed = vehicle.drivingSpeed?.takeIf { it.fresh(nowMs) } ?: return null
        return if (speed.raw > 0L) MOVING else UNRESTRICTED
    }

    private companion object {
        const val UNRESTRICTED = 0
        const val NO_VIDEO = 1
        const val NO_KEYBOARD = 2
        const val NO_CONFIG = 8
        const val LIMIT_MESSAGE_LENGTH = 16
        const val MOVING = NO_VIDEO or NO_KEYBOARD or NO_CONFIG or LIMIT_MESSAGE_LENGTH
    }
}
