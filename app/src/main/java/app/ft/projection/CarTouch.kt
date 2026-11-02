package app.ft.projection

data class CarTouch(val action: Int, val x: Int, val y: Int, val x2: Int = -1, val y2: Int = -1, val index: Int = 0) {
    companion object {
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val POINTER_DOWN = 5
        const val POINTER_UP = 6
    }
}
