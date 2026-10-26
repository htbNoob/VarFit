package com.varfit.app

import android.content.Context
import com.varfit.core.AavsoSettings
import com.varfit.core.Chi2MapResult
import com.varfit.core.Derived
import com.varfit.core.ErrorSource
import com.varfit.core.EventKind
import com.varfit.core.FitData
import com.varfit.core.FitResult
import com.varfit.core.KvwResult
import com.varfit.core.Periodogram
import com.varfit.core.Session

/** Process-wide state (the activity handles configuration changes itself). */
object AppState {
    val session = Session()
    val aavso = AavsoSettings()
    var eventKind: EventKind = EventKind.AUTO

    // fit panel
    var modelId: String? = null
    /** The model instance belonging to [params] (some models keep internal scales). */
    var model: com.varfit.core.FitModel? = null
    var showAllModels = false
    var params: DoubleArray? = null
    var fixed: BooleanArray? = null
    var lower: DoubleArray? = null
    var upper: DoubleArray? = null
    var fitData: FitData? = null
    var result: FitResult? = null
    var derived: List<Derived> = emptyList()
    var ranking: List<FitResult> = emptyList()
    var rankingEvents: List<com.varfit.core.EventTiming?> = emptyList()
    var rankingFailures: List<Pair<String, String>> = emptyList()
    var map: Chi2MapResult? = null
    var mapProfile = true
    var mapWide = false
    var mapGrid = 21
    var mapX = -1
    var mapY = -1
    var periodogram: Periodogram? = null
    var pgFmin: Double? = null
    var pgFmax: Double? = null
    var kvw: KvwResult? = null
    var autoGrid = 17
    var plotMode = 0 // 0 light curve, 1 χ² map, 2 periodogram

    /** Clears everything derived from data/fits (after loading a file or changing the target). */
    fun resetFits() {
        params = null; fixed = null; lower = null; upper = null; model = null
        fitData = null; result = null; derived = emptyList()
        ranking = emptyList(); rankingEvents = emptyList(); rankingFailures = emptyList()
        map = null; periodogram = null; kvw = null; plotMode = 0
    }

    fun resetModelParams() {
        model = null; params = null; fixed = null; lower = null; upper = null; result = null; derived = emptyList(); map = null
    }
}

object Prefs {
    private const val NAME = "varfit"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val a = AppState.aavso
        a.obsCode = p.getString("obsCode", a.obsCode) ?: ""
        a.filter = p.getString("filter", a.filter) ?: "TG"
        a.dateType = p.getString("dateType", a.dateType) ?: "JD"
        a.chart = p.getString("chart", a.chart) ?: "na"
        a.notes = p.getString("notes", a.notes) ?: ""
        a.exoFilter = p.getString("exoFilter", a.exoFilter) ?: "CV"
        a.binning = p.getString("binning", a.binning) ?: "1x1"
        a.secondaryObsCodes = p.getString("secObs", a.secondaryObsCodes) ?: ""
        a.exoNotes = p.getString("exoNotes", a.exoNotes) ?: ""
        a.errorSource = runCatching { ErrorSource.valueOf(p.getString("errSrc", a.errorSource.name)!!) }.getOrDefault(ErrorSource.LARGER)
        val s = AppState.session
        if (p.contains("lat")) s.site.lat = p.getFloat("lat", 0f).toDouble()
        if (p.contains("lon")) s.site.lon = p.getFloat("lon", 0f).toDouble()
        s.filter.minSnr = p.getFloat("minSnr", s.filter.minSnr.toFloat()).toDouble()
        s.filter.minRefs = p.getInt("minRefs", s.filter.minRefs)
        s.filter.maxErr = p.getFloat("maxErr", s.filter.maxErr.toFloat()).toDouble()
        s.scaleErrors = p.getBoolean("scaleErr", s.scaleErrors)
        s.useReportedErrors = p.getBoolean("useRepErr", s.useReportedErrors)
    }

    fun save(ctx: Context) {
        val a = AppState.aavso
        val s = AppState.session
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().apply {
            putString("obsCode", a.obsCode)
            putString("filter", a.filter)
            putString("dateType", a.dateType)
            putString("chart", a.chart)
            putString("notes", a.notes)
            putString("exoFilter", a.exoFilter)
            putString("binning", a.binning)
            putString("secObs", a.secondaryObsCodes)
            putString("exoNotes", a.exoNotes)
            putString("errSrc", a.errorSource.name)
            if (s.site.lat != null) putFloat("lat", s.site.lat!!.toFloat()) else remove("lat")
            if (s.site.lon != null) putFloat("lon", s.site.lon!!.toFloat()) else remove("lon")
            putFloat("minSnr", s.filter.minSnr.toFloat())
            putInt("minRefs", s.filter.minRefs)
            putFloat("maxErr", s.filter.maxErr.toFloat())
            putBoolean("scaleErr", s.scaleErrors)
            putBoolean("useRepErr", s.useReportedErrors)
            apply()
        }
    }
}
