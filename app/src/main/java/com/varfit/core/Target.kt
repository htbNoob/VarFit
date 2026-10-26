package com.varfit.core

import kotlin.math.floor
import kotlin.math.roundToLong

/** What kind of light-curve feature the user expects. */
enum class StarClass(val label: String) {
    DSCT("Pulsating: δ Scuti / HADS / SX Phe"),
    RRLYR("Pulsating: RR Lyrae"),
    CEP("Pulsating: Cepheid"),
    LPV("Long-period / semiregular (Mira, SR)"),
    ECL("Eclipsing binary"),
    TRANSIT("Exoplanet transit"),
    OTHER("Other / unknown");

    val isPulsator get() = this == DSCT || this == RRLYR || this == CEP || this == LPV

    /** Default event: maxima for pulsators, minima for eclipsing binaries, mid-transit for planets. */
    val defaultEvent: EventKind
        get() = when (this) {
            DSCT, RRLYR, CEP, LPV -> EventKind.MAX
            ECL -> EventKind.MIN
            TRANSIT -> EventKind.TRANSIT
            OTHER -> EventKind.AUTO
        }

    val defaultTimeSystem: TimeSystem
        get() = if (this == TRANSIT) TimeSystem.BJD_TDB else TimeSystem.HJD_UTC

    companion object {
        /** Maps a VSX variability type string (e.g. "HADS", "EA", "RRAB") to a class. */
        fun fromVsxType(type: String?): StarClass {
            if (type.isNullOrBlank()) return OTHER
            val t = type.uppercase().replace(":", "").trim()
            return when {
                t.startsWith("HADS") || t.startsWith("DSCT") || t.startsWith("SXPHE") || t.startsWith("DSCTC") -> DSCT
                t.startsWith("RR") -> RRLYR
                t.startsWith("DCEP") || t.startsWith("CEP") || t.startsWith("CW") || t.startsWith("RV") || t.startsWith("BLAP") -> CEP
                t.startsWith("M") && !t.startsWith("MISC") -> LPV
                t.startsWith("SR") || t.startsWith("L") -> LPV
                t.startsWith("EP") -> TRANSIT
                t.startsWith("E") || t.contains("EA") || t.contains("EB") || t.contains("EW") -> ECL
                else -> OTHER
            }
        }
    }
}

/** Which instant is extracted from a fit. */
enum class EventKind(val label: String) {
    MAX("Time of maximum (brightest)"),
    MIN("Time of minimum (faintest)"),
    TRANSIT("Mid-transit time"),
    AUTO("Automatic (largest excursion)");
}

/** Everything known about the target: user input plus catalog lookups. All fields are editable. */
class TargetInfo {
    var name: String = ""
    var starClass: StarClass = StarClass.OTHER
    var period: Double? = null
    var periodErr: Double? = null
    /** Reference epoch (max for pulsators, min for EBs, Tc for transits). */
    var epoch: Double? = null
    var epochErr: Double? = null
    var epochSystem: TimeSystem = TimeSystem.HJD_UTC

    // VSX
    var vsxName: String? = null
    var auid: String? = null
    var vsxType: String? = null
    var magRange: String? = null
    var catalogNote: String? = null

    // exoplanet
    var planetName: String? = null
    var hostName: String? = null
    var rpRs: Double? = null
    var aRs: Double? = null
    var inclination: Double? = null
    var impact: Double? = null
    var t14Hours: Double? = null
    var depthPct: Double? = null
    var teff: Double? = null
    var u1: Double = 0.45
    var u2: Double = 0.27

    fun hasEphemeris() = period != null && epoch != null && period!! > 0
}

object Ephemeris {
    /** Cycle number nearest to time t. */
    fun cycle(t: Double, epoch: Double, period: Double): Long = ((t - epoch) / period).roundToLong()

    fun calculated(t: Double, epoch: Double, period: Double): Double = epoch + period * cycle(t, epoch, period)

    /** (cycle, calculated, O−C in days). */
    fun oMinusC(observed: Double, epoch: Double, period: Double): Triple<Long, Double, Double> {
        val e = cycle(observed, epoch, period)
        val c = epoch + period * e
        return Triple(e, c, observed - c)
    }

    fun phase(t: Double, epoch: Double, period: Double): Double {
        val x = (t - epoch) / period
        return x - floor(x)
    }

    /** All predicted events inside [t0, t1]. */
    fun eventsIn(t0: Double, t1: Double, epoch: Double, period: Double, max: Int = 50): List<Double> {
        if (period <= 0) return emptyList()
        val out = ArrayList<Double>()
        var e = kotlin.math.ceil((t0 - epoch) / period).toLong()
        while (out.size < max) {
            val t = epoch + e * period
            if (t > t1) break
            out.add(t)
            e++
        }
        return out
    }
}

/** Approximate quadratic limb-darkening coefficients (V/G band, log g≈4.5, solar metallicity). Editable by the user. */
object LimbDarkening {
    private val teffs = doubleArrayOf(3500.0, 4000.0, 4500.0, 5000.0, 5500.0, 6000.0, 6500.0, 7000.0, 7500.0)
    private val u1s = doubleArrayOf(0.60, 0.72, 0.66, 0.58, 0.50, 0.42, 0.36, 0.31, 0.27)
    private val u2s = doubleArrayOf(0.20, 0.07, 0.12, 0.18, 0.24, 0.29, 0.32, 0.34, 0.35)

    fun forTeff(teff: Double): Pair<Double, Double> {
        val t = teff.coerceIn(teffs.first(), teffs.last())
        var i = 0
        while (i < teffs.size - 2 && t > teffs[i + 1]) i++
        val f = (t - teffs[i]) / (teffs[i + 1] - teffs[i])
        return Pair(u1s[i] + f * (u1s[i + 1] - u1s[i]), u2s[i] + f * (u2s[i + 1] - u2s[i]))
    }
}
