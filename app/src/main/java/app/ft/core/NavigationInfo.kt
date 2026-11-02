package app.ft.core

/** Maneuver numbers use Android Auto's NavigationManeuver.Type enum. Null distance is unknown. */
data class NavigationInfo(
    val active: Boolean = false,
    val maneuver: Int = 0,
    val road: String = "",
    val exit: Int = 0,
    val distanceMeters: Int? = null,
    val timeSeconds: Int = 0
)
