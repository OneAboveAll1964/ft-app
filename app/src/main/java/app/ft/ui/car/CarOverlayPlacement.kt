package app.ft.ui.car

/** Avoid covering the top corners of a projected navigation app with FT's return pill.
 * In mirror mode preserve the user's existing button position.
 */
internal object CarOverlayPlacement {
    fun corner(requested: Int, aa: Boolean): Int {
        val bounded = requested.coerceIn(0, 3)
        return if (aa && bounded < 2) bounded + 2 else bounded
    }
}
