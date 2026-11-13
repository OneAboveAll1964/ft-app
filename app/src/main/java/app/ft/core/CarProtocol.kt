package app.ft.core

/** Stable preference IDs, independent of the CarLife transport (hotspot/direct/USB). */
enum class CarProtocol(val id: String) {
    CARLIFE("carlife"), ICCOA("iccoa");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: CARLIFE
    }
}
