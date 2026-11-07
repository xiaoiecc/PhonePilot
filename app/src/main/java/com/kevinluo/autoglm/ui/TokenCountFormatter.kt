package com.kevinluo.autoglm.ui

import java.util.Locale
import kotlin.math.round

/** Decimal SI units; round at the displayed precision before choosing the final unit. */
internal fun formatTokenCount(tokens: Long?): String {
    if (tokens == null) return "—"
    val count = tokens.coerceAtLeast(0)
    if (count < 1000) return count.toString()
    val units = arrayOf("K", "M", "B", "T", "P", "E")
    var value = count.toDouble() / 1000
    var unit = 0
    while ((value >= 1000 || round(value * 10) / 10 >= 1000) && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return String.format(Locale.ROOT, "%.1f%s", value, units[unit])
}
