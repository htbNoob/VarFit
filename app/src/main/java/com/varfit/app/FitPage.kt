package com.varfit.app

import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import com.varfit.core.Analysis
import com.varfit.core.AutoFit
import com.varfit.core.Chi2MapResult
import com.varfit.core.Chi2Mapper
import com.varfit.core.Ephemeris
import com.varfit.core.FitContext
import com.varfit.core.FitData
import com.varfit.core.FitModel
import com.varfit.core.Fitter
import com.varfit.core.Fmt
import com.varfit.core.ModelRegistry
import com.varfit.core.StarClass

// ---------------------------------------------------------------------------------- shared helpers

fun defaultModelId(c: StarClass): String = when (c) {
    StarClass.DSCT, StarClass.RRLYR, StarClass.CEP -> "fourier2"
    StarClass.LPV -> "poly3"
    StarClass.ECL -> "mikulasek"
    StarClass.TRANSIT -> "ldtransit"
    StarClass.OTHER -> "poly4"
}

fun fitContext(fd: FitData): FitContext {
    val s = AppState.session
    val ev = FitContext.resolveEvent(AppState.eventKind, fd)
    val epoch = s.epochInFitSystem()?.minus(fd.tRef)
    return FitContext(fd, s.target, ev, epoch)
}

/** Makes sure AppState holds a model and a parameter set compatible with it. */
fun ensureModel(fd: FitData, ctx: FitContext): FitModel {
    val st = AppState
    val id = st.modelId ?: defaultModelId(st.session.target.starClass).also { st.modelId = it }
    var m = st.model
    if (m == null || m.id != id) {
        m = ModelRegistry.create(id)
        st.model = m
        st.params = null
    }
    val p = st.params
    if (p == null || p.size != m.nParams) {
        st.params = m.initial(ctx)
        st.fixed = m.defaultFixed(ctx)
        st.lower = m.lower(ctx)
        st.upper = m.upper(ctx)
        st.result = null
        st.derived = emptyList()
    }
    return m
}

private fun interpX(fd: FitData, t: Double): Double {
    if (!fd.hasAirmass || fd.n == 0) return 0.0
    val idx = fd.t.indices.sortedBy { fd.t[it] }
    if (t <= fd.t[idx.first()]) return fd.x[idx.first()]
    if (t >= fd.t[idx.last()]) return fd.x[idx.last()]
    for (k in 1 until idx.size) {
        val a = idx[k - 1]; val b = idx[k]
        if (t <= fd.t[b]) {
            val f = if (fd.t[b] > fd.t[a]) (t - fd.t[a]) / (fd.t[b] - fd.t[a]) else 0.0
            return fd.x[a] + f * (fd.x[b] - fd.x[a])
        }
    }
    return 0.0
}

/** Fills a plot with the data set and, optionally, the current model, residuals and event markers. */
fun MainActivity.fillLightPlot(plot: PlotView, withModel: Boolean) {
    val s = AppState.session
    val ds = s.dataset ?: run { plot.clear("No data loaded"); return }
    plot.mode = PlotView.Mode.LIGHT
    plot.points = ds.obs.map { o ->
        val t = s.time(o)
        PlotView.Pt(t, o.mag, if (o.magErr.isFinite()) o.magErr else Double.NaN, s.isUsed(o) && s.inWindow(t), o.index)
    }
    val tRef = s.referenceTime()
    plot.tRefPlot = tRef
    plot.timeLabel = "hours from ${s.timeSystem.label} ${Fmt.f(tRef, 3)}"
    plot.window = if (s.windowStart != null || s.windowEnd != null)
        Pair(s.windowStart ?: plot.points.minOf { it.t }, s.windowEnd ?: plot.points.maxOf { it.t }) else null
    plot.onPointTap = { idx ->
        if (!busy) {
            if (!s.manualExcluded.add(idx)) s.manualExcluded.remove(idx)
            AppState.result = null
            refresh()
        }
    }
    val marks = ArrayList<Pair<Double, String>>()
    val t0 = plot.points.minOf { it.t }
    val t1 = plot.points.maxOf { it.t }
    val per = s.target.period
    val ep = s.epochInFitSystem()
    if (per != null && ep != null && per > 0) {
        for (t in Ephemeris.eventsIn(t0, t1, ep, per, 20)) marks.add(Pair(t, "C"))
    }
    plot.binT = null; plot.binM = null; plot.curveT = null; plot.curveM = null; plot.residT = null; plot.residM = null
    val fd = AppState.fitData
    if (fd != null && s.binSize > 1) {
        plot.binT = DoubleArray(fd.n) { fd.t[it] + fd.tRef }
        plot.binM = fd.y.copyOf()
    }
    if (withModel && fd != null) {
        val m = AppState.model
        val p = AppState.result?.params ?: AppState.params
        if (m != null && p != null && p.size == m.nParams) {
            val a = fd.tMin; val b = fd.tMax
            val n = 600
            val ts = DoubleArray(n) { a + (b - a) * it / (n - 1).toDouble() }
            plot.curveT = DoubleArray(n) { ts[it] + fd.tRef }
            plot.curveM = DoubleArray(n) { m.value(ts[it], interpX(fd, ts[it]), p) }
            if (AppState.result != null) {
                plot.residT = DoubleArray(fd.n) { fd.t[it] + fd.tRef }
                plot.residM = DoubleArray(fd.n) { fd.y[it] - m.value(fd.t[it], fd.x[it], p) }
            }
            AppState.derived.firstOrNull { it.isTime && it.value.isFinite() }?.let { marks.add(Pair(it.value, "fit")) }
        }
    }
    plot.eventMarks = marks
    plot.invalidate()
}

private fun MainActivity.plotBox(plot: PlotView, heightDp: Int): PlotView {
    plot.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp))
    return plot
}

// ---------------------------------------------------------------------------------- the page

fun MainActivity.buildFitPage(root: LinearLayout) {
    val st = AppState
    val s = st.session
    if (s.dataset == null) {
        root.addView(card(heading("No data"), note("Load a Seestar CSV on the Data page first."),
            button("Go to Data", true) { showPage(0) }))
        return
    }
    val fd: FitData = try {
        s.fitData()
    } catch (e: Exception) {
        root.addView(card(heading("Cannot build fit data"), note(e.message ?: "", Pal.bad),
            button("Reset fit window") { s.windowStart = null; s.windowEnd = null; refresh() }))
        return
    }
    st.fitData = fd
    val ctx = fitContext(fd)
    val model = ensureModel(fd, ctx)
    val params = st.params!!
    val fixed = st.fixed!!
    val lower = st.lower!!
    val upper = st.upper!!

    // ------------------------------------------------------------ plot
    val plot = PlotView(this)
    val plotCard = card()
    val modeRow = hbox()
    val modes = listOf("Light curve", "χ² map", "Periodogram")
    for ((i, name) in modes.withIndex()) {
        modeRow.addView(button(name, primary = st.plotMode == i) { st.plotMode = i; refresh() })
    }
    plotCard.addView(modeRow)
    when (st.plotMode) {
        1 -> {
            val mp = st.map
            if (mp == null || mp.model.id != model.id) plot.clear("Compute a χ² map below") else configureMapPlot(plot, mp, fd)
        }
        2 -> {
            val pg = st.periodogram
            if (pg == null) plot.clear("Compute a periodogram below") else {
                plot.mode = PlotView.Mode.PERIODOGRAM
                plot.pgF = pg.freq; plot.pgP = pg.power; plot.pgMark = pg.bestFrequency
            }
        }
        else -> fillLightPlot(plot, withModel = true)
    }
    plotCard.addView(plotBox(plot, 300))
    plotCard.addView(note(when (st.plotMode) {
        0 -> "Tap a point to exclude/include it. Orange: model (initial guess until fitted). Green dashed: catalog prediction (C) and fitted event."
        1 -> "White contours: Δχ² = 2.30, 6.18, 11.8 (1σ, 2σ, 3σ joint). Cross: grid minimum."
        else -> "Generalised Lomb–Scargle periodogram of the fit data."
    }))
    root.addView(plotCard)

    // ------------------------------------------------------------ fit function panel
    val models = if (st.showAllModels) ModelRegistry.all() else ModelRegistry.forClass(s.target.starClass, fd.n)
    val modelList = if (models.none { it.id == model.id }) models + model else models
    val panel = card(heading("Fit function"))
    panel.addView(spinner(modelList.map { it.name }, modelList.indexOfFirst { it.id == model.id }) { i ->
        st.modelId = modelList[i].id
        st.resetModelParams()
        refresh()
    }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)) })
    panel.addView(checkBox("Show all models (not only those for ${s.target.starClass.label})", st.showAllModels) {
        st.showAllModels = it; refresh()
    })
    panel.addView(mono(model.formula).apply { setPadding(0, dp(6), 0, dp(8)) })

    // parameter table
    val header = hbox()
    fun hcell(t: String, w: Float) = textView(t, 12f, Pal.muted, bold = true).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
    }
    header.addView(hcell("Param", 1.1f)); header.addView(hcell("Value", 2.6f)); header.addView(hcell("± error", 1.6f))
    header.addView(hcell("Fix", 0.7f)); header.addView(hcell("Min", 1.4f)); header.addView(hcell("Max", 1.4f))
    panel.addView(header)
    val valEdits = ArrayList<EditText>()
    val minEdits = ArrayList<EditText>()
    val maxEdits = ArrayList<EditText>()
    val fixBoxes = ArrayList<CheckBox>()
    val res = st.result
    for ((i, ps) in model.params.withIndex()) {
        val row = hbox().apply { setPadding(0, dp(2), 0, dp(2)) }
        val off = if (ps.isTime) fd.tRef else 0.0
        row.addView(textView(ps.name + if (ps.unit.isNotEmpty()) "\n" + ps.unit else "", 12f, Pal.text).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
        })
        val shownValue = if (ps.isTime) Fmt.f(params[i] + off, 6) else Fmt.full(params[i])
        val ve = editText(shownValue, numeric = true).apply { textSize = 13f; tag = shownValue }
        row.addView(ve, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.6f).apply { rightMargin = dp(4) })
        val errText = when {
            res == null -> ""
            res.fixed[i] -> "fixed"
            !res.errors[i].isFinite() -> "?"
            ps.isTime -> Fmt.f(res.errors[i] * 1440, 2) + " min"
            else -> Fmt.auto(res.errors[i])
        }
        row.addView(textView(errText, 12f, Pal.muted).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f)
        })
        val cb = CheckBox(this).apply {
            isChecked = fixed[i]
            buttonTintList = android.content.res.ColorStateList.valueOf(Pal.accent)
        }
        row.addView(cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.7f))
        fun bound(v: Double) = if (v.isInfinite()) "" else if (ps.isTime) Fmt.f(v + off, 6) else Fmt.full(v)
        val mi = editText(bound(lower[i]), numeric = true, hint = "−∞").apply { textSize = 11f; tag = text.toString() }
        val ma = editText(bound(upper[i]), numeric = true, hint = "+∞").apply { textSize = 11f; tag = text.toString() }
        row.addView(mi, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { rightMargin = dp(3) })
        row.addView(ma, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        panel.addView(row)
        valEdits.add(ve); minEdits.add(mi); maxEdits.add(ma); fixBoxes.add(cb)
    }
    if (fd.hasAirmass.not() && model.params.any { it.name == "kX" })
        panel.addView(note("Airmass term kX is fixed at 0: set your location on the Target page to use it."))

    // options
    panel.addView(divider())
    panel.addView(checkBox("Scale errors by √(χ²/ndf) (recommended: Seestar errors are not calibrated)", s.scaleErrors) { s.scaleErrors = it })
    panel.addView(checkBox("Weight by Seestar Mag(err) (off: equal weights from the data scatter)", s.useReportedErrors) {
        s.useReportedErrors = it; st.result = null; refresh()
    })
    val wFrom = field("Fit window from [h]", s.windowStart?.let { Fmt.f((it - fd.tRef) * 24, 3) } ?: "", numeric = true, hint = "start")
    val wTo = field("to [h]", s.windowEnd?.let { Fmt.f((it - fd.tRef) * 24, 3) } ?: "", numeric = true, hint = "end")
    panel.addView(columns(wFrom.view, wTo.view))
    panel.addView(note("Hours relative to ${fd.system.label} ${Fmt.f(fd.tRef, 3)} (same axis as the plot). Empty = all data. ${fd.n} points in the fit${if (s.binSize > 1) " (binned ×${s.binSize})" else ""}."))

    commit = {
        val p = st.params
        val fx = st.fixed
        val lo = st.lower
        val hi = st.upper
        if (p != null && fx != null && lo != null && hi != null && p.size == valEdits.size) {
            // only fields the user actually edited are written back (no precision loss from display rounding)
            fun changed(e: EditText) = e.text.toString() != (e.tag as? String)
            for ((i, ps) in model.params.withIndex()) {
                val off = if (ps.isTime) fd.tRef else 0.0
                if (changed(valEdits[i])) Fmt.parse(valEdits[i].text.toString())?.let { p[i] = it - off }
                fx[i] = fixBoxes[i].isChecked
                if (changed(minEdits[i])) lo[i] = Fmt.parse(minEdits[i].text.toString())?.minus(off) ?: Double.NEGATIVE_INFINITY
                if (changed(maxEdits[i])) hi[i] = Fmt.parse(maxEdits[i].text.toString())?.minus(off) ?: Double.POSITIVE_INFINITY
            }
        }
        val a = wFrom.number
        val b = wTo.number
        val newStart = a?.let { fd.tRef + it / 24.0 }
        val newEnd = b?.let { fd.tRef + it / 24.0 }
        if (newStart != s.windowStart || newEnd != s.windowEnd) {
            s.windowStart = newStart; s.windowEnd = newEnd
            st.result = null
        }
    }

    panel.addView(buttonRows(2,
        button("Fit", primary = true) { doFit() },
        button("Auto-fit all models") { doAutoFit() },
        button("Reset start values") { commit(); st.resetModelParams(); commit = {}; refresh() },
        button("Full time range") { s.windowStart = null; s.windowEnd = null; st.result = null; commit = {}; refresh() }
    ))
    root.addView(panel)

    // ------------------------------------------------------------ results
    if (res != null && res.model.id == model.id) {
        val rc = card(heading("Result"))
        val ok = if (res.converged) Pal.good else Pal.warn
        rc.addView(textView("${res.message} · ${res.iterations} iterations", 13f, ok))
        rc.addView(mono(
            "χ² = ${Fmt.f(res.chi2, 2)}   ndf = ${res.ndf}   χ²/ndf = ${Fmt.f(res.chi2r, 3)}\n" +
            "BIC = ${Fmt.f(res.bic, 2)}   AIC = ${Fmt.f(res.aic, 2)}   rms = ${Fmt.f(res.residualRms * 1000, 1)} mmag"
        ))
        if (res.atBound.isNotEmpty())
            rc.addView(note("At a bound: " + res.atBound.joinToString { model.params[it].name } + " — widen the limits or fix it.", Pal.warn))
        rc.addView(divider())
        for (dv in st.derived) {
            val value = when {
                !dv.value.isFinite() -> "—"
                dv.isTime -> Fmt.valueErr(dv.value, dv.err, 5) + if (dv.err.isFinite()) "  (±${Fmt.f(dv.err * 1440, 2)} min)" else ""
                else -> Fmt.valueErr(dv.value, dv.err, 4)
            }
            val line = textView("${dv.label}: $value ${dv.unit}".trim(), 14f, Pal.text)
            rc.addView(line)
            if (dv.note.isNotEmpty()) rc.addView(note(dv.note))
        }
        rc.addView(buttonRows(2,
            button("Save fit report") { exportReport(save = true) },
            button("Go to Export") { showPage(3) }
        ))
        root.addView(rc)
    }

    // ------------------------------------------------------------ ranking
    if (st.ranking.isNotEmpty()) {
        val rk = card(heading("Model ranking (auto-fit)"))
        rk.addView(note("Sorted by BIC = χ² + k·ln n, which penalises extra parameters (lowest is best; ΔBIC > 6 is strong evidence). Tap a row to load that fit."))
        val best = st.ranking.first().bic
        for ((i, r) in st.ranking.withIndex()) {
            val ev = st.rankingEvents.getOrNull(i)
            val evTxt = if (ev != null && ev.tRel.isFinite()) "  t=${Fmt.f(ev.tRel + fd.tRef, 5)}" + (if (ev.err.isFinite()) "±${Fmt.f(ev.err * 1440, 1)}m" else "") else ""
            val row = textView(
                "${i + 1}. ${r.model.name}\n    χ²/ndf ${Fmt.f(r.chi2r, 3)}   ΔBIC ${Fmt.f(r.bic - best, 1)}$evTxt",
                13f, if (r.model.id == model.id) Pal.accent else Pal.text
            ).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = roundRect(if (i % 2 == 0) Pal.surface2 else Pal.surface, dp(6).toFloat())
                setOnClickListener {
                    if (!busy) runTask("Loading ${r.model.name}…", { _, _ ->
                        Analysis.derive(r, fd, s.target, ctx.epochRel, ctx.event)
                    }) { der ->
                        st.modelId = r.model.id
                        st.model = r.model
                        st.params = r.params.copyOf(); st.fixed = r.fixed.copyOf()
                        st.lower = r.lower.copyOf(); st.upper = r.upper.copyOf()
                        st.result = r
                        st.derived = der
                        st.plotMode = 0
                        commit = {}
                        refresh()
                    }
                }
            }
            rk.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) })
        }
        st.rankingFailures.forEach { rk.addView(note("${it.first}: ${it.second}", Pal.bad)) }
        rk.addView(labeledSpinner("Grid size for the global search", listOf("11 × 11 (fast)", "17 × 17", "25 × 25 (thorough)"),
            listOf(11, 17, 25).indexOf(st.autoGrid).coerceAtLeast(0)) { st.autoGrid = listOf(11, 17, 25)[it] })
        root.addView(rk)
    }

    // ------------------------------------------------------------ χ² map
    val names = model.params.map { it.name }
    val pair = model.mapPair(fixed)
    if (st.mapX !in names.indices || st.mapY !in names.indices || st.mapX == st.mapY || st.map?.model?.id != model.id && st.map != null) {
        st.mapX = pair.first; st.mapY = pair.second
    }
    val mc = card(heading("2D χ² map"))
    mc.addView(note("Scans two parameters on a grid. With 'profile' the other free parameters are re-fitted at every grid point, so the minimum of the map is the global minimum within the scanned box."))
    mc.addView(columns(
        labeledSpinner("x parameter", names, st.mapX) { st.mapX = it },
        labeledSpinner("y parameter", names, st.mapY) { st.mapY = it }
    ))
    mc.addView(labeledSpinner("Grid", listOf("11 × 11", "21 × 21", "31 × 31", "41 × 41"), listOf(11, 21, 31, 41).indexOf(st.mapGrid).coerceAtLeast(1)) {
        st.mapGrid = listOf(11, 21, 31, 41)[it]
    })
    mc.addView(checkBox("Profile (re-fit other parameters at each point)", st.mapProfile) { st.mapProfile = it })
    mc.addView(checkBox("Wide range (global search) instead of ±5σ around the fit", st.mapWide || st.result == null) { st.mapWide = it })
    st.map?.takeIf { it.model.id == model.id }?.let { mp ->
        val px = model.params[mp.ix]; val py = model.params[mp.iy]
        val bx = mp.bestX + if (px.isTime) fd.tRef else 0.0
        val by = mp.bestY + if (py.isTime) fd.tRef else 0.0
        mc.addView(mono("min χ² = ${Fmt.f(mp.minChi2, 2)} at ${px.name} = ${Fmt.auto(bx)}, ${py.name} = ${Fmt.auto(by)}"))
    }
    mc.addView(buttonRows(2,
        button("Compute map", primary = true) { doMap() },
        button("Fit from map minimum") {
            val mp = st.map
            if (mp == null || mp.model.id != model.id) toast("Compute a map first") else {
                st.params = mp.bestParams.copyOf(); commit = {}; doFit()
            }
        }
    ))
    root.addView(mc)

    // ------------------------------------------------------------ periodogram & KvW
    val pc = card(heading("Period search & symmetric extremum"))
    val fminDef = st.pgFmin ?: Fmt.roundUp(0.5 / fd.span.coerceAtLeast(1e-3), 0.01)
    val fmaxDef = st.pgFmax ?: 50.0
    val fMin = field("f min [c/d]", Fmt.f(fminDef, 3), numeric = true)
    val fMax = field("f max [c/d]", Fmt.f(fmaxDef, 2), numeric = true)
    pc.addView(columns(fMin.view, fMax.view))
    val cyc = s.target.period?.let { fd.span / it }
    pc.addView(note(if (cyc != null && cyc < 2) "Your data cover ${Fmt.f(cyc, 2)} cycles of the catalog period: too short for a reliable period from this night alone." else "A period needs at least ~2 cycles in the data."))
    st.periodogram?.let { pg -> pc.addView(mono("Best: f = ${Fmt.f(pg.bestFrequency, 5)} c/d, P = ${Fmt.f(pg.bestPeriod, 6)} d")) }
    st.kvw?.let { k -> pc.addView(mono("Kwee–van Woerden: ${Fmt.f(k.tRel + fd.tRef, 5)} ± ${Fmt.f(k.err * 1440, 2)} min (${fd.system.label}, ${k.pairs} pairs)")) }
    pc.addView(buttonRows(2,
        button("Periodogram") {
            val a = fMin.number; val b = fMax.number
            if (a == null || b == null || b <= a) { toast("Check the frequency range"); return@button }
            st.pgFmin = a; st.pgFmax = b
            runTask("Periodogram…", { _, _ ->
                Analysis.periodogram(fd.t, fd.y, fd.sigma, a, b, 6000)
            }) { pg -> st.periodogram = pg; st.plotMode = 2; refresh() }
        },
        button("Use best period") {
            val pg = st.periodogram
            if (pg == null) toast("Compute a periodogram first") else {
                s.target.period = pg.bestPeriod; st.resetModelParams(); toast("Period set to ${Fmt.f(pg.bestPeriod, 6)} d"); refresh()
            }
        },
        button("Kwee–van Woerden") {
            val k = Analysis.kweeVanWoerden(fd.t, fd.y)
            if (k == null) message("Kwee–van Woerden", "Not enough points or no symmetric extremum in the window. Restrict the fit window to the minimum/maximum.")
            st.kvw = k; refresh()
        }
    ))
    pc.addView(note("KvW assumes a symmetric extremum: restrict the fit window to the branch around the minimum or maximum."))
    root.addView(pc)
}

private fun MainActivity.configureMapPlot(plot: PlotView, mp: Chi2MapResult, fd: FitData) {
    plot.mode = PlotView.Mode.MAP
    plot.map = mp
    val px = mp.model.params[mp.ix]
    val py = mp.model.params[mp.iy]
    // time parameters: show minutes relative to the grid minimum
    if (px.isTime) { plot.mapXOffset = -mp.bestX; plot.mapXScale = 1440.0; plot.mapXLabel = "${px.name} − ${Fmt.f(mp.bestX + fd.tRef, 5)} [min]" }
    else { plot.mapXOffset = 0.0; plot.mapXScale = 1.0; plot.mapXLabel = px.name + if (px.unit.isNotEmpty()) " [${px.unit}]" else "" }
    if (py.isTime) { plot.mapYOffset = -mp.bestY; plot.mapYScale = 1440.0; plot.mapYLabel = "${py.name} − ${Fmt.f(mp.bestY + fd.tRef, 5)} [min]" }
    else { plot.mapYOffset = 0.0; plot.mapYScale = 1.0; plot.mapYLabel = py.name + if (py.unit.isNotEmpty()) " [${py.unit}]" else "" }
}

// ---------------------------------------------------------------------------------- actions

fun MainActivity.doFit() {
    val st = AppState
    runCatching { commit() }
    val fd = try { st.session.fitData() } catch (e: Exception) { message("Fit", e.message ?: ""); return }
    st.fitData = fd
    val ctx = fitContext(fd)
    val model = ensureModel(fd, ctx)
    val p0 = st.params!!.copyOf()
    val fx = st.fixed!!.copyOf()
    val lo = st.lower!!.copyOf()
    val hi = st.upper!!.copyOf()
    if (fx.all { it }) { toast("All parameters are fixed"); }
    val scale = st.session.scaleErrors
    runTask("Fitting ${model.name}…", { _, cancel ->
        val r = Fitter.fit(model, fd, p0, fx, lo, hi, scale, cancel = cancel)
        val der = Analysis.derive(r, fd, st.session.target, ctx.epochRel, ctx.event)
        Pair(r, der)
    }) { (r, der) ->
        st.result = r
        st.derived = der
        st.params = r.params.copyOf()
        st.plotMode = 0
        commit = {}
        refresh()
    }
}

fun MainActivity.doAutoFit() {
    val st = AppState
    runCatching { commit() }
    val fd = try { st.session.fitData() } catch (e: Exception) { message("Auto-fit", e.message ?: ""); return }
    st.fitData = fd
    val ctx = fitContext(fd)
    val models = ModelRegistry.forClass(st.session.target.starClass, fd.n)
    val scale = st.session.scaleErrors
    val grid = st.autoGrid
    runTask("Auto-fit: ${models.size} models with ${grid}×$grid grid search…", { progress, cancel ->
        val out = AutoFit.run(models, ctx, scale, grid, cancel) { _, f -> progress(f) }
        val events = out.results.map { r -> runCatching { Analysis.eventTime(r, fd, ctx.event) }.getOrNull() }
        val bestDerived = out.results.firstOrNull()?.let { Analysis.derive(it, fd, st.session.target, ctx.epochRel, ctx.event) }
        Triple(out, events, bestDerived)
    }) { (out, events, bestDerived) ->
        st.ranking = out.results
        st.rankingEvents = events
        st.rankingFailures = out.failures
        val best = out.results.firstOrNull()
        if (best != null) {
            st.modelId = best.model.id
            st.model = best.model
            st.params = best.params.copyOf(); st.fixed = best.fixed.copyOf()
            st.lower = best.lower.copyOf(); st.upper = best.upper.copyOf()
            st.result = best
            st.derived = bestDerived ?: emptyList()
        }
        st.plotMode = 0
        commit = {}
        refresh()
    }
}

fun MainActivity.doMap() {
    val st = AppState
    runCatching { commit() }
    val fd = try { st.session.fitData() } catch (e: Exception) { message("χ² map", e.message ?: ""); return }
    st.fitData = fd
    val ctx = fitContext(fd)
    val model = ensureModel(fd, ctx)
    val ix = st.mapX; val iy = st.mapY
    if (ix == iy || ix !in 0 until model.nParams || iy !in 0 until model.nParams) { toast("Choose two different parameters"); return }
    val base = (st.result?.takeIf { it.model.id == model.id }?.params ?: st.params!!).copyOf()
    val err = st.result?.takeIf { it.model.id == model.id }?.errors
    val wide = st.mapWide || err == null
    val lo = st.lower!!.copyOf(); val hi = st.upper!!.copyOf()
    val xr = Chi2Mapper.defaultRange(model, ix, base, err, fd, lo, hi, wide)
    val yr = Chi2Mapper.defaultRange(model, iy, base, err, fd, lo, hi, wide)
    val fx = st.fixed!!.copyOf()
    val g = st.mapGrid
    val profile = st.mapProfile
    val scale = st.result?.takeIf { it.errorsScaled && it.model.id == model.id }?.chi2r ?: 1.0
    runTask("χ² map ${g}×$g${if (profile) " (profile)" else ""}…", { progress, cancel ->
        Chi2Mapper.run(model, fd, base, fx, lo, hi, ix, iy, xr, yr, g, g, profile, scale, cancel, progress)
    }) { mp ->
        if (mp != null) { st.map = mp; st.plotMode = 1 }
        commit = {}
        refresh()
    }
}

/** Builds the fit report text; saves or shares it. */
fun MainActivity.exportReport(save: Boolean) {
    val st = AppState
    val r = st.result
    val fd = st.fitData
    if (r == null || fd == null) { toast("Run a fit first"); return }
    val txt = com.varfit.core.Report.fitReport(st.session, r, fd, st.derived, software) +
        (if (st.ranking.isNotEmpty()) "\nAuto-fit ranking:\n" + com.varfit.core.Report.ranking(st.ranking) else "")
    val name = "VarFit_${(st.session.target.vsxName ?: st.session.target.name).replace(' ', '_')}_${r.model.id}.txt"
    if (save) saveText(name, txt) else shareText(name, txt)
}
