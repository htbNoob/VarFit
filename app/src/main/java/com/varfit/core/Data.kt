package com.varfit.core

import kotlin.math.abs
import kotlin.math.sqrt

/** One photometric measurement. [jdUtc] is the mid-exposure Julian Date in UTC. */
data class Observation(
    val index: Int,
    val jdUtc: Double,
    val expSec: Double,
    val mag: Double,
    val magErr: Double,
    val snr: Double,
    val nRef: Int,
    val label: String
)

class Dataset(
    val name: String,
    val ra: Double,
    val dec: Double,
    val obs: List<Observation>,
    val sourceName: String,
    val format: String,
    val warnings: List<String>
) {
    val exposureSec: Double get() = obs.map { it.expSec }.filter { it > 0 }.let { if (it.isEmpty()) Double.NaN else it.sorted()[it.size / 2] }
    val firstJd: Double get() = obs.minOf { it.jdUtc }
    val lastJd: Double get() = obs.maxOf { it.jdUtc }
}

class ImportException(msg: String) : Exception(msg)

/**
 * Reads Seestar "Magnitude_*.csv" exports and simple generic CSV light curves
 * (any file with a JD/HJD/BJD column and a magnitude column).
 */
object CsvImport {

    private fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun splitLine(line: String, delim: Char): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQ = false
        for (ch in line) {
            when {
                ch == '"' -> inQ = !inQ
                ch == delim && !inQ -> { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(ch)
            }
        }
        out.add(sb.toString().trim())
        return out
    }

    private fun num(s: String?, commaDecimal: Boolean): Double? {
        if (s == null) return null
        var t = s.trim().removeSuffix("s").removeSuffix("S").trim()
        if (commaDecimal) t = t.replace(',', '.')
        return t.toDoubleOrNull()
    }

    fun parse(text: String, fileName: String): Dataset {
        val lines = text.removePrefix("﻿").lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.size < 2) throw ImportException("The file contains no data rows.")
        val header = lines[0]
        val delim = listOf(',', ';', '\t').maxByOrNull { d -> header.count { it == d } } ?: ','
        val commaDecimal = delim != ','
        val cols = splitLine(header, delim).map { key(it) }

        fun col(vararg names: String): Int {
            for (n in names) { val i = cols.indexOf(n); if (i >= 0) return i }
            return -1
        }

        val cName = col("name", "star", "starid", "object")
        val cType = col("type")
        val cRa = col("ra", "ra2000", "radeg")
        val cDec = col("dec", "dec2000", "decdeg")
        val cRefs = col("validreferencestars", "nref")
        val cMag = col("magobs", "mag", "magnitude", "vmag", "m")
        val cErr = col("magerr", "err", "error", "merr", "magerror", "sigma")
        val cSnr = col("snr")
        val cExp = col("exp", "exptime", "exposure")
        val cStartJd = col("expstartjd")
        val cEndJd = col("expendjd")
        val cStartLbl = col("expstart", "date")
        val cJd = col("jd", "jdutc", "jdmid", "time", "date")
        val cHjd = col("hjd", "hjdutc")
        val cBjd = col("bjd", "bjdtdb", "btjd")

        if (cMag < 0) throw ImportException("No magnitude column found (expected e.g. 'Mag(obs)').")
        val seestar = cStartJd >= 0
        val warnings = ArrayList<String>()

        data class Row(val name: String, val type: String, val jd: Double, val exp: Double, val mag: Double, val err: Double,
                       val snr: Double, val nref: Int, val ra: Double?, val dec: Double?, val label: String)

        val rows = ArrayList<Row>()
        var inputSystem = TimeSystem.JD_UTC
        for ((ln, line) in lines.drop(1).withIndex()) {
            val f = splitLine(line, delim)
            fun s(i: Int) = if (i >= 0 && i < f.size) f[i] else null
            val mag = num(s(cMag), commaDecimal) ?: continue
            if (!mag.isFinite() || mag > 40 || mag < -30) continue
            val exp = num(s(cExp), commaDecimal) ?: Double.NaN
            val jd: Double? = when {
                seestar -> {
                    val a = num(s(cStartJd), commaDecimal)
                    val b = num(s(cEndJd), commaDecimal)
                    when {
                        a != null && b != null -> 0.5 * (a + b)
                        a != null && exp.isFinite() -> a + exp / 172800.0
                        else -> a
                    }
                }
                cBjd >= 0 -> { inputSystem = TimeSystem.BJD_TDB; num(s(cBjd), commaDecimal) }
                cHjd >= 0 -> { inputSystem = TimeSystem.HJD_UTC; num(s(cHjd), commaDecimal) }
                cJd >= 0 -> num(s(cJd), commaDecimal)
                else -> null
            }
            if (jd == null || !jd.isFinite()) continue
            val fixedJd = when {
                jd < 100000 && jd > 40000 -> jd + 2400000.5   // MJD
                jd < 40000 && jd > 0 -> jd + 2457000.0        // BTJD-like offsets
                else -> jd
            }
            rows.add(
                Row(
                    name = s(cName) ?: fileName.substringBeforeLast('.'),
                    type = s(cType) ?: "",
                    jd = fixedJd,
                    exp = exp,
                    mag = mag,
                    err = num(s(cErr), commaDecimal) ?: Double.NaN,
                    snr = num(s(cSnr), commaDecimal) ?: Double.NaN,
                    nref = num(s(cRefs), commaDecimal)?.toInt() ?: -1,
                    ra = num(s(cRa), commaDecimal),
                    dec = num(s(cDec), commaDecimal),
                    label = s(cStartLbl) ?: "row ${ln + 2}"
                )
            )
        }
        if (rows.isEmpty()) throw ImportException("No usable rows (need a time column and a magnitude column).")

        // Keep only the target star rows: drop reference stars, then take the most frequent name.
        var targetRows = rows.filter { r -> val t = r.type.lowercase(); !(t.contains("reference") && !t.contains("non")) }
        if (targetRows.isEmpty()) targetRows = rows
        val names = targetRows.groupingBy { it.name }.eachCount()
        val name = names.maxByOrNull { it.value }!!.key
        if (names.size > 1) warnings.add("File contains ${names.size} objects; using '$name' (${names[name]} rows).")
        targetRows = targetRows.filter { it.name == name }.sortedBy { it.jd }

        val ras = targetRows.mapNotNull { it.ra }.sorted()
        val decs = targetRows.mapNotNull { it.dec }.sorted()
        val ra = if (ras.isEmpty()) Double.NaN else ras[ras.size / 2]
        val dec = if (decs.isEmpty()) Double.NaN else decs[decs.size / 2]
        if (ra.isNaN()) warnings.add("No coordinates in file: enter RA/Dec on the Target page for catalog lookup and time corrections.")

        val obs = targetRows.mapIndexed { i, r ->
            var jdUtc = r.jd
            if (inputSystem != TimeSystem.JD_UTC && !ra.isNaN()) {
                jdUtc = r.jd - TimeSystems.offset(r.jd, ra, dec, TimeSystem.JD_UTC, inputSystem)
            }
            Observation(i, jdUtc, r.exp, r.mag, r.err, r.snr, r.nref, r.label)
        }
        if (inputSystem != TimeSystem.JD_UTC) warnings.add("Times were given as ${inputSystem.label} and converted back to JD (UTC).")
        if (obs.any { !it.magErr.isFinite() }) warnings.add("Some rows have no magnitude error; the scatter of the data will be used instead.")
        return Dataset(name, ra, dec, obs, fileName, if (seestar) "Seestar photometry CSV" else "Generic CSV", warnings)
    }
}

class QualityFilter(
    var minSnr: Double = 10.0,
    var minRefs: Int = 5,
    var maxErr: Double = 0.3
)

class Site(var lat: Double? = null, var lon: Double? = null) {
    val isSet get() = lat != null && lon != null
}

/** Data prepared for fitting: times are in days relative to [tRef] in time system [system]. */
class FitData(
    val t: DoubleArray,
    val y: DoubleArray,
    val sigma: DoubleArray,
    /** airmass − 1 (0 when unknown) */
    val x: DoubleArray,
    val tRef: Double,
    val system: TimeSystem,
    val obsIndex: IntArray,
    val hasAirmass: Boolean,
    val equalWeights: Boolean
) {
    val n get() = t.size
    val tMin get() = t.minOrNull() ?: 0.0
    val tMax get() = t.maxOrNull() ?: 0.0
    val span get() = tMax - tMin
    fun absTime(rel: Double) = rel + tRef
    fun relTime(abs: Double) = abs - tRef
}

/** All user state that is independent of the Android UI. */
class Session {
    var dataset: Dataset? = null
    val manualExcluded = HashSet<Int>()
    val filter = QualityFilter()
    val target = TargetInfo()
    val site = Site()
    var timeSystem: TimeSystem = TimeSystem.HJD_UTC
    var useReportedErrors = true
    var scaleErrors = true
    /** Fit window in absolute time of [timeSystem]; null = all data. */
    var windowStart: Double? = null
    var windowEnd: Double? = null

    fun load(ds: Dataset) {
        dataset = ds
        raOverride = null
        decOverride = null
        manualExcluded.clear()
        windowStart = null
        windowEnd = null
        target.name = ds.name
    }

    /** User-entered coordinates override the ones from the file. */
    var raOverride: Double? = null
    var decOverride: Double? = null
    val ra: Double get() = raOverride ?: dataset?.ra ?: Double.NaN
    val dec: Double get() = decOverride ?: dataset?.dec ?: Double.NaN

    /**
     * Reference time for fits: depends only on the whole data set and the time system, so fitted
     * parameters stay valid when the fit window, exclusions or binning change.
     */
    fun referenceTime(): Double {
        val ds = dataset ?: return 0.0
        val mid = ds.obs[ds.obs.size / 2]
        return kotlin.math.floor(time(mid) * 1000.0) / 1000.0
    }
    val hasCoords: Boolean get() = ra.isFinite() && dec.isFinite()

    fun passesQuality(o: Observation): Boolean {
        if (o.snr.isFinite() && o.snr < filter.minSnr) return false
        if (o.nRef >= 0 && o.nRef < filter.minRefs) return false
        if (o.magErr.isFinite() && o.magErr > filter.maxErr) return false
        return true
    }

    fun isUsed(o: Observation) = passesQuality(o) && o.index !in manualExcluded

    /** Time of an observation in the chosen system. */
    fun time(o: Observation, sys: TimeSystem = timeSystem): Double =
        if (hasCoords) TimeSystems.convert(o.jdUtc, ra, dec, sys) else o.jdUtc

    fun inWindow(t: Double) = (windowStart == null || t >= windowStart!!) && (windowEnd == null || t <= windowEnd!!)

    fun airmass(o: Observation): Double {
        if (!site.isSet || !hasCoords) return Double.NaN
        return TimeSystems.airmass(o.jdUtc, ra, dec, site.lat!!, site.lon!!)
    }

    /** Builds the fit data from used observations inside the window. */
    fun fitData(): FitData {
        val ds = dataset ?: throw IllegalStateException("No data loaded")
        val used = ds.obs.filter { isUsed(it) && inWindow(time(it)) }
        if (used.size < 3) throw IllegalStateException("Fewer than 3 usable points in the fit window.")
        val times = used.map { time(it) }
        val tRef = referenceTime()
        val mags = used.map { it.mag }
        val med = mags.sorted()[mags.size / 2]
        // robust scatter estimate for points without errors / equal weights
        val diffs = (1 until mags.size).map { abs(mags[it] - mags[it - 1]) }.sorted()
        val scatter = if (diffs.isEmpty()) 0.05 else (diffs[diffs.size / 2] / 0.6745 / sqrt(2.0)).coerceAtLeast(1e-4)
        val equal = !useReportedErrors || used.any { !it.magErr.isFinite() || it.magErr <= 0 }
        val sig = used.map { if (equal) scatter else it.magErr }
        val am = used.map { airmass(it) }
        val hasAm = am.all { it.isFinite() }
        if (med.isNaN()) throw IllegalStateException("Invalid magnitudes")

        // optional binning of consecutive points (fit only; exports always use the individual points)
        val b = binSize.coerceAtLeast(1)
        val groups = used.indices.chunked(b).filter { it.size * 2 >= b || b == 1 }
        if (groups.size < 3) throw IllegalStateException("Fewer than 3 bins; reduce the bin size.")
        return FitData(
            t = DoubleArray(groups.size) { g -> groups[g].map { times[it] }.average() - tRef },
            y = DoubleArray(groups.size) { g -> groups[g].map { mags[it] }.average() },
            sigma = DoubleArray(groups.size) { g -> sqrt(groups[g].sumOf { sig[it] * sig[it] }) / groups[g].size },
            x = DoubleArray(groups.size) { g -> if (hasAm) groups[g].map { am[it] }.average() - 1.0 else 0.0 },
            tRef = tRef,
            system = timeSystem,
            obsIndex = IntArray(groups.size) { g -> used[groups[g][0]].index },
            hasAirmass = hasAm,
            equalWeights = equal
        )
    }

    /** Number of consecutive points averaged per bin for fitting (1 = no binning). */
    var binSize: Int = 1

    /** Catalog epoch converted to the fitting time system (or null). */
    fun epochInFitSystem(): Double? {
        val e = target.epoch ?: return null
        val ds = dataset ?: return e
        if (!hasCoords) return e
        return e + TimeSystems.offset(ds.firstJd, ra, dec, target.epochSystem, timeSystem)
    }
}
