package com.varfit.app

import android.view.ViewGroup
import android.widget.LinearLayout
import com.varfit.core.Fmt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private fun jdToUtcString(jd: Double): String {
    val ms = ((jd - 2440587.5) * 86400000.0).toLong()
    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    f.timeZone = TimeZone.getTimeZone("UTC")
    return f.format(Date(ms)) + " UTC"
}

fun MainActivity.buildDataPage(root: LinearLayout) {
    val st = AppState
    val s = st.session
    root.addView(card(
        heading("Light-curve data"),
        note("Open the CSV exported by the Seestar app's photometry feature (Magnitude_….csv). Other CSV files with a JD/HJD/BJD column and a magnitude column work too. You can also share a CSV to VarFit from a file manager."),
        buttonRows(2,
            button("Open CSV…", primary = true) { openCsv() },
            button("Load example (DY Peg)") { loadExample() }
        )
    ))
    val ds = s.dataset ?: run {
        root.addView(note("No data loaded yet."))
        return
    }

    val used = ds.obs.count { s.isUsed(it) }
    val info = card(heading(ds.name))
    val spanH = (ds.lastJd - ds.firstJd) * 24
    val lines = buildList {
        add("File: ${ds.sourceName} (${ds.format})")
        add(if (s.hasCoords) "RA ${Fmt.f(s.ra, 5)}°, Dec ${Fmt.f(s.dec, 5)}° (J2000)" else "No coordinates")
        add("Points: ${ds.obs.size} total, $used used, ${ds.obs.size - used} excluded")
        add("Start: ${jdToUtcString(ds.firstJd)}  (JD ${Fmt.f(ds.firstJd, 5)})")
        add("Span: ${Fmt.f(spanH, 2)} h" + if (ds.exposureSec.isFinite()) " · exposure ${Fmt.f(ds.exposureSec, 1)} s" else "")
        val mags = ds.obs.filter { s.isUsed(it) }.map { it.mag }
        if (mags.isNotEmpty()) add("Magnitude range: ${Fmt.f(mags.minOrNull()!!, 3)} – ${Fmt.f(mags.maxOrNull()!!, 3)}")
    }
    info.addView(textView(lines.joinToString("\n"), 14f))
    info.addView(note("The Seestar's readable time column is local time; VarFit uses the JD columns (UTC) and converts them to HJD/BJD."))
    ds.warnings.forEach { info.addView(note("⚠ $it", Pal.warn)) }
    root.addView(info)

    // plot
    val plot = PlotView(this)
    st.fitData = runCatching { s.fitData() }.getOrNull()
    fillLightPlot(plot, withModel = false)
    plot.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(280))
    root.addView(card(
        heading("Light curve"),
        plot,
        note("Filled: used · hollow: excluded (quality filter, tap, or outside the fit window). Tap a point to toggle it. Diamonds: binned points used for fitting."),
        buttonRows(2,
            button("Include all tapped points") { s.manualExcluded.clear(); st.result = null; refresh() },
            button("Continue to Target") { showPage(1) }
        )
    ))

    // quality
    val q = card(heading("Quality filter & weighting"))
    val fSnr = field("Min. SNR", Fmt.f(s.filter.minSnr, 1), numeric = true)
    val fRefs = field("Min. reference stars", s.filter.minRefs.toString(), numeric = true)
    val fErr = field("Max. Mag(err)", Fmt.f(s.filter.maxErr, 3), numeric = true)
    q.addView(columns(fSnr.view, fRefs.view, fErr.view))
    val bins = listOf(1, 2, 3, 4, 5, 10, 20)
    q.addView(labeledSpinner("Bin consecutive points for fitting", bins.map { if (it == 1) "no binning" else "$it points" },
        bins.indexOf(s.binSize).coerceAtLeast(0)) {
        s.binSize = bins[it]; st.result = null; st.ranking = emptyList()
        refresh()
    })
    q.addView(note("Binning speeds up fitting of long series and reduces scatter. AAVSO files always contain the individual points."))
    q.addView(button("Apply filter", primary = true) {
        commit(); st.result = null; st.ranking = emptyList(); refresh()
    })
    commit = {
        fSnr.number?.let { s.filter.minSnr = it }
        fRefs.number?.let { s.filter.minRefs = it.toInt() }
        fErr.number?.let { s.filter.maxErr = it }
    }
    root.addView(q)
}
