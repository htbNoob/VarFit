package com.varfit.app

import com.varfit.core.Fmt
import com.varfit.core.LimbDarkening
import com.varfit.core.StarClass
import com.varfit.core.TargetInfo
import com.varfit.core.TimeSystem
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class VsxEntry(
    val name: String, val auid: String?, val ra: Double?, val dec: Double?, val type: String?,
    val period: Double?, val epoch: Double?, val maxMag: String?, val minMag: String?, val distArcsec: Double?
) {
    fun summary(): String {
        val sb = StringBuilder(name)
        type?.let { sb.append(" · ").append(it) }
        period?.let { sb.append(" · P=").append(Fmt.auto(it)).append(" d") }
        if (maxMag != null || minMag != null) sb.append(" · ").append(maxMag ?: "?").append(" – ").append(minMag ?: "?")
        distArcsec?.let { sb.append(" · ").append(Fmt.f(it, 1)).append("\"") }
        return sb.toString()
    }

    fun applyTo(t: TargetInfo) {
        t.vsxName = name
        t.auid = auid
        t.vsxType = type
        t.magRange = listOfNotNull(maxMag, minMag).joinToString(" – ")
        if (period != null) t.period = period
        if (epoch != null) { t.epoch = epoch; t.epochSystem = TimeSystem.HJD_UTC }
        val c = StarClass.fromVsxType(type)
        if (c != StarClass.OTHER) t.starClass = c
        t.catalogNote = "VSX: " + summary()
    }
}

class ExoEntry(
    val planet: String, val host: String, val ra: Double?, val dec: Double?, val period: Double?, val periodErr: Double?,
    val tc: Double?, val tcErr: Double?, val t14h: Double?, val depthPct: Double?, val rpRs: Double?, val aRs: Double?,
    val inc: Double?, val b: Double?, val teff: Double?, val vmag: Double?, val distArcsec: Double?
) {
    fun summary(): String {
        val sb = StringBuilder(planet)
        period?.let { sb.append(" · P=").append(Fmt.f(it, 5)).append(" d") }
        depthPct?.let { sb.append(" · depth ").append(Fmt.f(it, 2)).append(" %") } ?: rpRs?.let { sb.append(" · depth≈").append(Fmt.f(it * it * 100, 2)).append(" %") }
        t14h?.let { sb.append(" · T14=").append(Fmt.f(it, 2)).append(" h") }
        vmag?.let { sb.append(" · V=").append(Fmt.f(it, 1)) }
        return sb.toString()
    }

    fun applyTo(t: TargetInfo) {
        t.planetName = planet
        t.hostName = host
        t.starClass = StarClass.TRANSIT
        t.period = period
        t.periodErr = periodErr
        t.epoch = tc
        t.epochErr = tcErr
        t.epochSystem = TimeSystem.BJD_TDB
        t.t14Hours = t14h
        t.depthPct = depthPct
        t.rpRs = rpRs ?: depthPct?.let { sqrt(it / 100.0) }
        t.aRs = aRs
        t.inclination = inc
        t.impact = b ?: if (aRs != null && inc != null) aRs * cos(inc * PI / 180) else null
        t.teff = teff
        teff?.let { val (u1, u2) = LimbDarkening.forTeff(it); t.u1 = u1; t.u2 = u2 }
        t.catalogNote = "NASA Exoplanet Archive: " + summary()
    }
}

/** Network lookups. Call from a background thread. */
object Catalogs {
    private const val VSX = "https://www.aavso.org/vsx/index.php"
    private const val TAP = "https://exoplanetarchive.ipac.caltech.edu/TAP/sync"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "VarFit-Android/1.0 (variable star light-curve fitter)")
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}: ${body.take(200)}")
            return body
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun angularSepArcsec(ra1: Double, dec1: Double, ra2: Double, dec2: Double): Double {
        val r = PI / 180
        val c = sin(dec1 * r) * sin(dec2 * r) + cos(dec1 * r) * cos(dec2 * r) * cos((ra1 - ra2) * r)
        return acos(c.coerceIn(-1.0, 1.0)) / r * 3600
    }

    /** Leading number of a VSX string such as "0.0729263" or "9.95 V" or "<12.1". */
    private fun leadingNumber(s: String?): Double? {
        if (s == null) return null
        val m = Regex("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?").find(s) ?: return null
        return m.value.toDoubleOrNull()
    }

    private fun JSONObject.ci(vararg keys: String): String? {
        val it = keys()
        val map = HashMap<String, String>()
        while (it.hasNext()) { val k = it.next(); map[k.lowercase(Locale.US)] = k }
        for (k in keys) {
            val real = map[k.lowercase(Locale.US)] ?: continue
            val v = opt(real)
            if (v == null || v == JSONObject.NULL) continue
            val s = v.toString().trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return null
    }

    /** Collects every JSON object that has a "Name" field, wherever it is nested. */
    private fun collectNamed(any: Any?, out: MutableList<JSONObject>) {
        when (any) {
            is JSONObject -> {
                if (any.ci("Name") != null && (any.ci("VariabilityType", "Period", "AUID", "OID") != null)) out.add(any)
                val it = any.keys()
                while (it.hasNext()) collectNamed(any.opt(it.next()), out)
            }
            is JSONArray -> for (i in 0 until any.length()) collectNamed(any.opt(i), out)
        }
    }

    private fun parseVsx(body: String, ra: Double?, dec: Double?): List<VsxEntry> {
        val trimmed = body.trim()
        val root: Any = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        val objs = ArrayList<JSONObject>()
        collectNamed(root, objs)
        return objs.map { o ->
            val ora = o.ci("RA2000", "RA", "RADeg")?.let { leadingNumber(it) }
            val odec = o.ci("Declination2000", "Dec2000", "Dec", "DecDeg")?.let { leadingNumber(it) }
            VsxEntry(
                name = o.ci("Name")!!,
                auid = o.ci("AUID"),
                ra = ora, dec = odec,
                type = o.ci("VariabilityType", "Type"),
                period = leadingNumber(o.ci("Period")),
                epoch = leadingNumber(o.ci("Epoch")),
                maxMag = o.ci("MaxMag"),
                minMag = o.ci("MinMag"),
                distArcsec = if (ra != null && dec != null && ora != null && odec != null) angularSepArcsec(ra, dec, ora, odec) else null
            )
        }.sortedBy { it.distArcsec ?: 0.0 }
    }

    fun vsxCone(ra: Double, dec: Double, radiusDeg: Double): List<VsxEntry> {
        val url = String.format(Locale.US, "%s?view=api.list&ra=%.6f&dec=%.6f&radius=%.5f&format=json", VSX, ra, dec, radiusDeg)
        return parseVsx(get(url), ra, dec)
    }

    fun vsxByName(name: String, ra: Double?, dec: Double?): List<VsxEntry> {
        val url = "$VSX?view=api.object&ident=${enc(name)}&format=json"
        return parseVsx(get(url), ra, dec)
    }

    private const val EXO_COLS = "pl_name,hostname,ra,dec,pl_orbper,pl_orbpererr1,pl_tranmid,pl_tranmiderr1,pl_trandur,pl_trandep,pl_ratror,pl_ratdor,pl_orbincl,pl_imppar,st_teff,sy_vmag"

    private fun parseExo(body: String, ra: Double?, dec: Double?): List<ExoEntry> {
        val arr = JSONArray(body.trim())
        val out = ArrayList<ExoEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            fun d(k: String): Double? = if (o.isNull(k)) null else o.optDouble(k).takeIf { it.isFinite() }
            val ora = d("ra"); val odec = d("dec")
            out.add(
                ExoEntry(
                    planet = o.optString("pl_name"), host = o.optString("hostname"), ra = ora, dec = odec,
                    period = d("pl_orbper"), periodErr = d("pl_orbpererr1"), tc = d("pl_tranmid"), tcErr = d("pl_tranmiderr1"),
                    t14h = d("pl_trandur"), depthPct = d("pl_trandep"), rpRs = d("pl_ratror"), aRs = d("pl_ratdor"),
                    inc = d("pl_orbincl"), b = d("pl_imppar"), teff = d("st_teff"), vmag = d("sy_vmag"),
                    distArcsec = if (ra != null && dec != null && ora != null && odec != null) angularSepArcsec(ra, dec, ora, odec) else null
                )
            )
        }
        return out.sortedWith(compareBy({ it.distArcsec ?: 0.0 }, { it.planet }))
    }

    fun exoCone(ra: Double, dec: Double, radiusDeg: Double): List<ExoEntry> {
        val q = String.format(Locale.US,
            "select %s from pscomppars where contains(point('icrs',ra,dec),circle('icrs',%.6f,%.6f,%.5f))=1", EXO_COLS, ra, dec, radiusDeg)
        return parseExo(get("$TAP?query=${enc(q)}&format=json"), ra, dec)
    }

    fun exoByName(name: String, ra: Double?, dec: Double?): List<ExoEntry> {
        val n = name.trim().replace("'", "''").uppercase(Locale.US)
        val q = "select $EXO_COLS from pscomppars where upper(pl_name) like '%$n%' or upper(hostname) like '%$n%'"
        return parseExo(get("$TAP?query=${enc(q)}&format=json"), ra, dec)
    }
}
