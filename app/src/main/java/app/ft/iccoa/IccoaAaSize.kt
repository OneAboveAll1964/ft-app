package app.ft.iccoa

import app.ft.aa.AaProtocol

/** Keep the car's aspect ratio inside the largest frame supported by our AA protocol. */
internal object IccoaAaSize {
    fun fit(width: Int, height: Int): Pair<Int, Int> {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val (maxWidth, maxHeight) = AaProtocol.standardSize(w, h)
        if (w <= maxWidth && h <= maxHeight) return w to h
        return if (w.toLong() * maxHeight >= h.toLong() * maxWidth) {
            maxWidth to even(h.toLong() * maxWidth / w)
        } else {
            even(w.toLong() * maxHeight / h) to maxHeight
        }
    }

    private fun even(value: Long): Int = (value.toInt() and -2).coerceAtLeast(2)
}
