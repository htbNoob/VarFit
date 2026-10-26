package com.varfit.app

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.varfit.core.AavsoExport
import com.varfit.core.ErrorSource
import com.varfit.core.ExportResult
import com.varfit.core.StarClass

internal var lastExport: ExportResult? = null

fun MainActivity.buildExportPage(root: LinearLayout) {
    val st = AppState
    val s = st.session
    val a = st.aavso
    if (s.dataset == null) {
        root.addView(card(heading("No data"), note("Load a CSV on the Data page first."), button("Go to Data", true) { showPage(0) }))
        return
    }

    // ------------------------------------------------------------ observer
    val c1 = card(heading("AAVSO observer"))
    val fCode = field("Observer code", a.obsCode, hint = "e.g. ABC")
    c1.addView(fCode.view)
    c1.addView(note("Your AAVSO observer code; it is remembered on this device."))
    root.addView(c1)

    // ------------------------------------------------------------ variable star photometry
    val c2 = card(heading("Photometry for WebObs (AAVSO Extended Format)"))
    val fStar = field("STARID (AAVSO / VSX name)", a.starId.ifBlank { s.target.vsxName ?: "" }, hint = s.target.vsxName ?: s.dataset!!.name)
    c2.addView(fStar.view)
    val filters = AavsoExport.EXTENDED_FILTERS
    c2.addView(columns(
        labeledSpinner("Filter", filters, filters.indexOf(a.filter).coerceAtLeast(0)) { a.filter = filters[it] },
        labeledSpinner("Date", listOf("JD", "HJD"), if (a.dateType == "HJD") 1 else 0) { a.dateType = if (it == 1) "HJD" else "JD" }
    ))
    c2.addView(note("Seestar images are one-shot-colour. TG (green channel, V comparison magnitudes) is the usual code; use CV if the Seestar magnitudes are unfiltered/clear. Check how your magnitudes were calibrated."))
    val fChart = field("CHART (AAVSO sequence ID, or descriptive name)", a.chart)
    val fK = field("Check star (KNAME)", a.checkName)
    val fKm = field("Check star magnitude (KMAG)", a.checkMag)
    c2.addView(fChart.view)
    c2.addView(columns(fK.view, fKm.view))
    val sources = ErrorSource.values().toList()
    c2.addView(labeledSpinner("MERR (uncertainty per point)", sources.map { it.label }, sources.indexOf(a.errorSource)) { a.errorSource = sources[it] })
    val fNotes = field("Notes (added to every line)", a.notes, hint = "e.g. Seestar S50, ensemble of Seestar reference stars")
    c2.addView(fNotes.view)
    c2.addView(buttonRows(1, button("Create AAVSO Extended file", primary = true) {
        commit()
        try {
            lastExport = AavsoExport.extended(s, a, software, st.result?.residualRms)
        } catch (e: Exception) { message("Export", e.message ?: "") }
        refresh()
    }))
    root.addView(c2)

    // ------------------------------------------------------------ exoplanet
    var exoFields: List<Field> = emptyList()
    if (s.target.starClass == StarClass.TRANSIT) {
        val c3 = card(heading("Transit for the AAVSO Exoplanet Database"))
        val fHost = field("STAR_NAME", a.exoStarName.ifBlank { s.target.hostName ?: "" }, hint = s.target.hostName ?: s.dataset!!.name)
        val fPl = field("EXOPLANET_NAME", a.exoPlanetName.ifBlank { s.target.planetName ?: "" }, hint = s.target.planetName ?: "e.g. WASP-12 b")
        c3.addView(columns(fHost.view, fPl.view))
        val ef = AavsoExport.EXO_FILTERS
        val binnings = listOf("1x1", "2x2", "3x3", "4x4")
        c3.addView(columns(
            labeledSpinner("Filter", ef, ef.indexOf(a.exoFilter).coerceAtLeast(0)) { a.exoFilter = ef[it] },
            labeledSpinner("Binning", binnings, binnings.indexOf(a.binning).coerceAtLeast(0)) { a.binning = binnings[it] }
        ))
        val fSec = field("Secondary observer codes (optional)", a.secondaryObsCodes)
        val fEN = field("Notes (weather, systematics…)", a.exoNotes)
        c3.addView(fSec.view)
        c3.addView(fEN.view)
        c3.addView(note("Times are BJD_TDB, values are raw normalised flux (Rnflux) with airmass as detrend column. The fitted transit (Fit page) defines the out-of-transit baseline and fills #RESULTS. WebObs also asks for a plate-solved field image showing target and comparison stars."))
        c3.addView(buttonRows(1, button("Create AAVSO exoplanet file", primary = true) {
            commit()
            try {
                val r = st.result?.takeIf { it.model.timingIndex != null }
                lastExport = AavsoExport.exoplanet(s, a, software, r, st.fitData, st.result?.residualRms)
            } catch (e: Exception) { message("Export", e.message ?: "") }
            refresh()
        }))
        exoFields = listOf(fHost, fPl, fSec, fEN)
        root.addView(c3)
    }

    // ------------------------------------------------------------ preview
    lastExport?.let { ex ->
        val c4 = card(heading("Preview: ${ex.suggestedName}"))
        c4.addView(note("${ex.rows} data lines"))
        ex.warnings.forEach { c4.addView(note("⚠ $it", Pal.warn)) }
        val preview = ex.text.lines().take(18).joinToString("\n") + if (ex.text.lines().size > 18) "\n…" else ""
        c4.addView(mono(preview).apply {
            background = roundRect(Pal.surface2, dp(8).toFloat())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })
        c4.addView(buttonRows(2,
            button("Save file…", primary = true) { saveText(ex.suggestedName, ex.text) },
            button("Share") { shareText(ex.suggestedName, ex.text) }
        ))
        root.addView(c4)
    }

    // ------------------------------------------------------------ report & plot
    val c5 = card(heading("Fit report & plot"))
    c5.addView(note(if (st.result == null) "Run a fit on the Fit page to export a report." else "Report of the current fit: model, χ², parameters, event time, O−C."))
    val plot = PlotView(this)
    plot.visibility = if (st.result != null) View.VISIBLE else View.GONE
    fillLightPlot(plot, withModel = true)
    plot.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240))
    c5.addView(plot)
    c5.addView(buttonRows(2,
        button("Save report…") { exportReport(save = true) },
        button("Share report") { exportReport(save = false) },
        button("Save plot (PNG)…") {
            if (plot.width == 0) toast("Nothing to save") else savePng("VarFit_plot.png", plot.toBitmap())
        }
    ))
    root.addView(c5)

    root.addView(note("Upload: AAVSO website → WebObs → file upload (photometry), or the exoplanet section of WebObs. Check the preview first; WebObs reports problems line by line."))

    commit = {
        a.obsCode = fCode.text.trim().uppercase()
        a.starId = fStar.text.trim()
        a.chart = fChart.text.trim().ifBlank { "na" }
        a.checkName = fK.text.trim().ifBlank { "na" }
        a.checkMag = fKm.text.trim().ifBlank { "na" }
        a.notes = fNotes.text.trim()
        if (exoFields.size == 4) {
            a.exoStarName = exoFields[0].text.trim()
            a.exoPlanetName = exoFields[1].text.trim()
            a.secondaryObsCodes = exoFields[2].text.trim()
            a.exoNotes = exoFields[3].text.trim()
        }
    }
}
