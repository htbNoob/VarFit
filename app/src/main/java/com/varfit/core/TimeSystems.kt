package com.varfit.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class TimeSystem(val label: String, val exoCode: String) {
    JD_UTC("JD (UTC)", "JD_UTC"),
    HJD_UTC("HJD (UTC)", "HJD_UTC"),
    BJD_TDB("BJD (TDB)", "BJD_TDB");
}

/**
 * Time-system conversions (JD_UTC → HJD_UTC / BJD_TDB) and airmass.
 *
 * Earth position: low-precision solar theory (Meeus ch. 25, ~0.01°), referred to J2000.
 * Sun–barycentre offset: Keplerian Jupiter, Saturn, Uranus, Neptune (JPL approximate elements).
 * Accuracy of the light-time correction is ~1 s, far below the timing precision of
 * small-telescope photometry. The observer's topocentric offset (≤ 21 ms) is ignored.
 */
object TimeSystems {
    private const val DEG = PI / 180.0
    const val AU_LIGHT_DAYS = 499.00478384 / 86400.0
    private const val EPS_J2000 = 23.4392911 * DEG

    /** (JD_UTC start, TAI−UTC seconds) – leap seconds since 1999. */
    private val leapTable = doubleArrayOf(
        2451179.5, 32.0, // 1999-01-01
        2453736.5, 33.0, // 2006-01-01
        2454832.5, 34.0, // 2009-01-01
        2456109.5, 35.0, // 2012-07-01
        2457204.5, 36.0, // 2015-07-01
        2457754.5, 37.0  // 2017-01-01
    )

    fun taiMinusUtc(jdUtc: Double): Double {
        var v = 32.0
        var i = 0
        while (i < leapTable.size) {
            if (jdUtc >= leapTable[i]) v = leapTable[i + 1]
            i += 2
        }
        return v
    }

    fun ttMinusUtcSeconds(jdUtc: Double) = taiMinusUtc(jdUtc) + 32.184

    fun tdbMinusTtSeconds(jdTt: Double): Double {
        val g = (357.53 + 0.98560028 * (jdTt - 2451545.0)) * DEG
        return 0.001657 * sin(g) + 0.000014 * sin(2 * g)
    }

    fun unitVector(raDeg: Double, decDeg: Double): DoubleArray {
        val ra = raDeg * DEG
        val dec = decDeg * DEG
        return doubleArrayOf(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
    }

    private fun eclToEq(x: Double, y: Double, z: Double) = doubleArrayOf(
        x,
        y * cos(EPS_J2000) - z * sin(EPS_J2000),
        y * sin(EPS_J2000) + z * cos(EPS_J2000)
    )

    /** Heliocentric position of the Earth, equatorial J2000, in AU. */
    fun earthHeliocentric(jdTt: Double): DoubleArray {
        val t = (jdTt - 2451545.0) / 36525.0
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = (357.52911 + 35999.05029 * t - 0.0001537 * t * t)
        val mr = m * DEG
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(mr) +
                (0.019993 - 0.000101 * t) * sin(2 * mr) + 0.000289 * sin(3 * mr)
        val trueLon = l0 + c
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val nu = (m + c) * DEG
        val r = 1.000001018 * (1 - e * e) / (1 + e * cos(nu))
        // refer longitude from equinox of date to J2000
        val year = 2000.0 + (jdTt - 2451545.0) / 365.25
        val lon2000 = (trueLon - 0.01397 * (year - 2000.0)) * DEG
        // Sun geocentric → Earth heliocentric is the opposite vector
        return eclToEq(-r * cos(lon2000), -r * sin(lon2000), 0.0)
    }

    // a, a', e, e', I, I', L, L', varpi, varpi', Omega, Omega'  (JPL, 1800–2050), plus mass ratio m/M_sun
    private val planets = arrayOf(
        doubleArrayOf(5.20288700, -0.00011607, 0.04838624, -0.00013253, 1.30439695, -0.00183714,
            34.39644051, 3034.74612775, 14.72847983, 0.21252668, 100.47390909, 0.20469106, 1.0 / 1047.3486),
        doubleArrayOf(9.53667594, -0.00125060, 0.05386179, -0.00050991, 2.48599187, 0.00193609,
            49.95424423, 1222.49362201, 92.59887831, -0.41897216, 113.66242448, -0.28867794, 1.0 / 3497.898),
        doubleArrayOf(19.18916464, -0.00196176, 0.04725744, -0.00004397, 0.77263783, -0.00242939,
            313.23810451, 428.48202785, 170.95427630, 0.40805281, 74.01692503, 0.04240589, 1.0 / 22902.98),
        doubleArrayOf(30.06992276, 0.00026291, 0.00859048, 0.00005105, 1.77004347, 0.00035372,
            -55.12002969, 218.45945325, 44.96476227, -0.32241464, 131.78422574, -0.00508664, 1.0 / 19412.24)
    )

    private fun planetHeliocentric(el: DoubleArray, t: Double): DoubleArray {
        val a = el[0] + el[1] * t
        val e = el[2] + el[3] * t
        val inc = (el[4] + el[5] * t) * DEG
        val lMean = el[6] + el[7] * t
        val varpi = el[8] + el[9] * t
        val node = (el[10] + el[11] * t) * DEG
        val w = varpi * DEG - node
        var mAnom = ((lMean - varpi) % 360.0) * DEG
        if (mAnom > PI) mAnom -= 2 * PI
        if (mAnom < -PI) mAnom += 2 * PI
        var ecc = mAnom + e * sin(mAnom)
        repeat(8) { ecc -= (ecc - e * sin(ecc) - mAnom) / (1 - e * cos(ecc)) }
        val xp = a * (cos(ecc) - e)
        val yp = a * sqrt(1 - e * e) * sin(ecc)
        val cw = cos(w); val sw = sin(w); val cO = cos(node); val sO = sin(node); val cI = cos(inc); val sI = sin(inc)
        val x = (cw * cO - sw * sO * cI) * xp + (-sw * cO - cw * sO * cI) * yp
        val y = (cw * sO + sw * cO * cI) * xp + (-sw * sO + cw * cO * cI) * yp
        val z = (sw * sI) * xp + (cw * sI) * yp
        return eclToEq(x, y, z)
    }

    /** Position of the Sun relative to the solar-system barycentre, equatorial J2000, AU. */
    fun sunBarycentric(jdTdb: Double): DoubleArray {
        val t = (jdTdb - 2451545.0) / 36525.0
        val s = DoubleArray(3)
        var mu = 0.0
        for (p in planets) {
            val r = planetHeliocentric(p, t)
            for (k in 0..2) s[k] -= p[12] * r[k]
            mu += p[12]
        }
        for (k in 0..2) s[k] /= (1 + mu)
        return s
    }

    /** Converts a mid-exposure JD (UTC) to the requested system for a target at (ra, dec) J2000 degrees. */
    fun convert(jdUtc: Double, raDeg: Double, decDeg: Double, to: TimeSystem): Double {
        if (to == TimeSystem.JD_UTC) return jdUtc
        val n = unitVector(raDeg, decDeg)
        val jdTt = jdUtc + ttMinusUtcSeconds(jdUtc) / 86400.0
        val earth = earthHeliocentric(jdTt)
        return when (to) {
            TimeSystem.HJD_UTC -> jdUtc + dot(earth, n) * AU_LIGHT_DAYS
            TimeSystem.BJD_TDB -> {
                val jdTdb = jdTt + tdbMinusTtSeconds(jdTt) / 86400.0
                val sun = sunBarycentric(jdTdb)
                val r = DoubleArray(3) { earth[it] + sun[it] }
                jdTdb + dot(r, n) * AU_LIGHT_DAYS
            }
            TimeSystem.JD_UTC -> jdUtc
        }
    }

    /** Offset (days) to add to a time in system [from] to express it in system [to], evaluated near jdUtc. */
    fun offset(jdUtc: Double, raDeg: Double, decDeg: Double, from: TimeSystem, to: TimeSystem): Double {
        if (from == to) return 0.0
        return convert(jdUtc, raDeg, decDeg, to) - convert(jdUtc, raDeg, decDeg, from)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Greenwich mean sidereal time in degrees (UT1 ≈ UTC). */
    fun gmstDeg(jdUtc: Double): Double {
        val d = jdUtc - 2451545.0
        val t = d / 36525.0
        var g = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38710000.0
        g %= 360.0
        if (g < 0) g += 360.0
        return g
    }

    /** Altitude of the target in degrees. Longitude east-positive. */
    fun altitudeDeg(jdUtc: Double, raDeg: Double, decDeg: Double, latDeg: Double, lonDeg: Double): Double {
        val lst = gmstDeg(jdUtc) + lonDeg
        val ha = (lst - raDeg) * DEG
        val lat = latDeg * DEG
        val dec = decDeg * DEG
        val s = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)
        return asin(s.coerceIn(-1.0, 1.0)) / DEG
    }

    /** Airmass (Pickering 2002), NaN below the horizon. */
    fun airmass(jdUtc: Double, raDeg: Double, decDeg: Double, latDeg: Double, lonDeg: Double): Double {
        val h = altitudeDeg(jdUtc, raDeg, decDeg, latDeg, lonDeg)
        if (h <= 0.5) return Double.NaN
        val arg = h + 244.0 / (165.0 + 47.0 * h.pow(1.1))
        return 1.0 / sin(arg * DEG)
    }

    fun isLeapSecondTableCurrent(jdUtc: Double) = abs(jdUtc - leapTable[leapTable.size - 2]) < 365.25 * 30
}
