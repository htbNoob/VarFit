package com.varfit.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max

/** Locale-independent number formatting (AAVSO files must always use '.' as decimal separator). */
object Fmt {
    fun f(v: Double, decimals: Int): String =
        if (!v.isFinite()) "na" else String.format(Locale.US, "%.${decimals}f", v)

    /** Decimals so that the error has two significant digits. */
    fun decimalsFor(err: Double, min: Int = 0, maxD: Int = 9): Int {
        if (!err.isFinite() || err <= 0) return -1
        val d = 1 - floor(log10(err)).toInt()
        return d.coerceIn(min, maxD)
    }

    fun valueErr(v: Double, err: Double, fallbackDecimals: Int = 5): String {
        if (!v.isFinite()) return "—"
        val d = decimalsFor(err)
        return if (d < 0) f(v, fallbackDecimals) else "${f(v, d)} ± ${f(err, d)}"
    }

    /** General display of a parameter value with sensible precision. */
    fun auto(v: Double): String {
        if (!v.isFinite()) return "—"
        val a = abs(v)
        return when {
            a == 0.0 -> "0"
            a >= 1e5 -> f(v, 6)
            a >= 100 -> f(v, 4)
            a >= 1 -> f(v, 5)
            a >= 1e-3 -> f(v, 6)
            else -> String.format(Locale.US, "%.4e", v)
        }
    }

    /** Full precision (15 significant digits), plain notation, '.' as decimal separator. */
    fun full(v: Double): String {
        if (!v.isFinite()) return ""
        if (v == 0.0) return "0"
        return java.math.BigDecimal(v).round(java.math.MathContext(15)).stripTrailingZeros().toPlainString()
    }

    /** Parses user input, accepting ',' as decimal separator. */
    fun parse(s: String?): Double? = s?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    fun minutes(days: Double) = days * 1440.0

    fun hoursText(days: Double): String = f(days * 24, 3) + " h"

    fun roundUp(v: Double, step: Double) = ceil(v / step) * step

    fun clip(s: String, n: Int) = if (s.length <= n) s else s.substring(0, max(0, n))
}
