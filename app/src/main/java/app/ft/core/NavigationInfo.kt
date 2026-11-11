package app.ft.core

data class NavigationInfo(
    val active: Boolean = false,
    val maneuver: Int = 0,
    val road: String = "",
    val exit: Int = 0,
    val distanceMeters: Int? = null,
    val timeSeconds: Int = 0
)
