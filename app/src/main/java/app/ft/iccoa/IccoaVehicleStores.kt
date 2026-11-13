package app.ft.iccoa

import android.content.Context
import app.ft.core.Prefs

/** Pairing and touch calibration belong to vehicles; the phone's ICCOA client identity stays global. */
object IccoaVehicleStores {
    internal fun name(base: String, vehicleProfileId: String): String {
        require(vehicleProfileId == Prefs.LEGACY_ID || runCatching {
            java.util.UUID.fromString(vehicleProfileId).toString() == vehicleProfileId
        }.getOrDefault(false))
        return if (vehicleProfileId == Prefs.LEGACY_ID) base else "$base-$vehicleProfileId"
    }

    fun clear(context: Context, vehicleProfileId: String) {
        listOf("iccoa-remembered-car", "iccoa-trusted-quick-v1", "iccoa-trusted-reconnect-v1", "ft-iccoa-touch")
            .forEach { base -> context.getSharedPreferences(name(base, vehicleProfileId), Context.MODE_PRIVATE)
                .edit().clear().apply() }
    }
}
