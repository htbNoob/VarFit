package com.varfit.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

enum class ErrorSource(val label: String) {
    REPORTED("Seestar Mag(err) per point"),
    SCATTER("Fit residual scatter (rms)"),
    LARGER("Larger of the two")
}

/** Observer settings for AAVSO files (persisted by the app). */
class AavsoSettings {
    var obsCode: String = ""
    var starId: String = ""
    var filter: String = "TG"
    var dateType: String = "JD"          // JD or HJD (AAVSO Extended allows JD, HJD, EXCEL)
    var chart: String = "na"
    var checkName: String = "na"
    var checkMag: String = "na"
    var notes: String = ""
    var errorSource: ErrorSource = ErrorSource.LARGER
    // exoplanet
    var exoStarName: String = ""
    var exoPlanetName: String = ""
    var exoFilter: String = "CV"
    var binning: String = "1x1"
    var secondaryObsCodes: String = ""
    var exoNotes: String = ""
}

class ExportResult(val text: String, val warnings: List<String>, val rows: Int, val suggestedName: String)

object AavsoExport {

    val EXTENDED_FILTERS = listOf("TG", "TB", "TR", "CV", "CR", "V", "B", "R", "I", "SG", "SR", "SI")
    val EXO_FILTERS = listOf("CV", "CBB", "TG", "TB", "TR", "V", "B", "R", "I", "SG", "SR", "SI", "O")

    private fun clean(s: String, delim: Char = ','): String =
        s.replace(delim, ';').replace('\n', ' ').replace('\r', ' ').replace("#", "").trim()

    private fun field(s: String, maxLen: Int): String {
        val c = clean(s)
        return if (c.isEmpty()) "na" else Fmt.clip(c, maxLen)
    }

    private fun errFor(o: Observation, scatter: Double?, src: ErrorSource): Double {
        val rep = o.magErr
        return when (src) {
            ErrorSource.REPORTED -> if (rep.isFinite()) rep else scatter ?: Double.NaN
            ErrorSource.SCATTER -> scatter ?: rep
            ErrorSource.LARGER -> when {
                rep.isFinite() && scatter != null -> max(rep, scatter)
                rep.isFinite() -> rep
                else -> scatter ?: Double.NaN
            }
        }
    }

    /** AAVSO Extended File Format (v1.2) for WebObs photometry upload. */
    fun extended(session: Session, st: AavsoSettings, software: String, scatter: Double?): ExportResult {
        val ds = session.dataset ?: throw IllegalStateException("No data loaded")
        val warn = ArrayList<String>()
        if (st.obsCode.isBlank()) warn.add("AAVSO observer code is empty – WebObs will reject the file.")
        val starId = st.starId.ifBlank { session.target.vsxName ?: ds.name }
        if (session.target.vsxName == null && st.starId.isBlank())
            warn.add("STARID '$starId' comes from the CSV; use the AAVSO/VSX name (look it up on the Target page).")
        val useHjd = st.dateType == "HJD" && session.hasCoords
        if (st.dateType == "HJD" && !session.hasCoords) warn.add("No coordinates: dates written as JD.")
        if (st.checkName == "na" || st.checkName.isBlank())
            warn.add("No check star given. AAVSO asks for a check star (KNAME/KMAG) with ensemble photometry.")
        if (st.chart.isBlank() || st.chart == "na")
            warn.add("CHART is 'na'. With the Seestar's own reference stars, describe the comparison set in NOTES.")

        val sb = StringBuilder()
        sb.append("#TYPE=EXTENDED\n")
        sb.append("#OBSCODE=").append(clean(st.obsCode).uppercase()).append('\n')
        sb.append("#SOFTWARE=").append(Fmt.clip(clean(software), 255)).append('\n')
        sb.append("#DELIM=,\n")
        sb.append("#DATE=").append(if (useHjd) "HJD" else "JD").append('\n')
        sb.append("#OBSTYPE=CCD\n")
        sb.append("#NAME,DATE,MAG,MERR,FILT,TRANS,MTYPE,CNAME,CMAG,KNAME,KMAG,AMASS,GROUP,CHART,NOTES\n")
        var rows = 0
        for (o in ds.obs) {
            if (!session.isUsed(o)) continue
            val date = if (useHjd) TimeSystems.convert(o.jdUtc, session.ra, session.dec, TimeSystem.HJD_UTC) else o.jdUtc
            val err = errFor(o, scatter, st.errorSource)
            val am = session.airmass(o)
            val noteParts = ArrayList<String>()
            if (st.notes.isNotBlank()) noteParts.add(clean(st.notes))
            if (o.nRef >= 0) noteParts.add("Seestar ensemble of ${o.nRef} ref stars")
            if (o.snr.isFinite()) noteParts.add("SNR " + Fmt.f(o.snr, 1))
            val notes = noteParts.joinToString("; ").ifBlank { "na" }
            val line = listOf(
                field(starId, 30),
                Fmt.f(date, 5),
                Fmt.f(o.mag, 3),
                if (err.isFinite()) Fmt.clip(Fmt.f(err, 3), 6) else "na",
                field(st.filter, 5),
                "NO",
                "STD",
                "ENSEMBLE",
                "na",
                field(st.checkName, 20),
                field(st.checkMag, 8),
                if (am.isFinite()) Fmt.f(am, 3) else "na",
                "na",
                field(st.chart, 20),
                notes
            )
            sb.append(line.joinToString(",")).append('\n')
            rows++
        }
        if (rows == 0) warn.add("No observations pass the quality filter.")
        val name = "AAVSO_${starId.replace(' ', '_')}_${Fmt.f(ds.firstJd, 2)}.txt"
        return ExportResult(sb.toString(), warn, rows, name)
    }

    /** Out-of-transit mask from a fit (or catalog prediction). */
    private fun transitWindow(session: Session, r: FitResult?, d: FitData?): Pair<Double, Double>? {
        if (r != null && d != null) {
            val m = r.model
            val p = r.params
            val tc = m.timingIndex?.let { p[it] + d.tRef } ?: return null
            val dur = when (m) {
                is LimbDarkenedTransitModel -> m.t14(p)
                is TrapezoidTransitModel -> p[5]
                is GaussianModel -> 5 * p[4]
                is MikulasekModel -> 5 * p[4]
                else -> session.target.t14Hours?.div(24.0) ?: return null
            }
            return Pair(tc, dur)
        }
        val per = session.target.period ?: return null
        val ep = session.epochInFitSystem() ?: return null
        val dur = session.target.t14Hours?.div(24.0) ?: return null
        val ds = session.dataset ?: return null
        val mid = session.time(ds.obs[ds.obs.size / 2])
        return Pair(Ephemeris.calculated(mid, ep, per), dur)
    }

    /** AAVSO Exoplanet Database report (WebObs exoplanet section). Raw (undetrended) normalised flux. */
    fun exoplanet(session: Session, st: AavsoSettings, software: String, r: FitResult?, d: FitData?, scatter: Double?): ExportResult {
        val ds = session.dataset ?: throw IllegalStateException("No data loaded")
        val tg = session.target
        val warn = ArrayList<String>()
        if (st.obsCode.isBlank()) warn.add("AAVSO observer code is empty – the upload will be rejected.")
        val sys = if (session.hasCoords) TimeSystem.BJD_TDB else TimeSystem.JD_UTC
        if (!session.hasCoords) warn.add("No coordinates: times written as JD_UTC.")
        val starName = st.exoStarName.ifBlank { tg.hostName ?: tg.vsxName ?: ds.name }
        val planet = st.exoPlanetName.ifBlank { tg.planetName ?: "$starName b" }

        val used = ds.obs.filter { session.isUsed(it) }
        val times = used.map { if (session.hasCoords) TimeSystems.convert(it.jdUtc, session.ra, session.dec, sys) else it.jdUtc }
        // transit window must be expressed in BJD_TDB as well
        var win = transitWindow(session, r, d)
        if (win != null && d != null && d.system != sys && session.hasCoords) {
            win = Pair(win.first + TimeSystems.offset(ds.firstJd, session.ra, session.dec, d.system, sys), win.second)
        }
        val oot = used.indices.filter { i -> win == null || abs(times[i] - win.first) > 0.55 * win.second }
        if (win == null) warn.add("No transit fit or catalog duration: normalised by the mean of all points.")
        if (oot.size < 3) warn.add("Fewer than 3 out-of-transit points; normalisation is unreliable.")
        val fluxes = used.map { 10.0.pow(-0.4 * it.mag) }
        val norm = (if (oot.size >= 3) oot.map { fluxes[it] } else fluxes).average()
        val am = used.map { session.airmass(it) }
        val hasAm = am.all { it.isFinite() }
        if (!hasAm) warn.add("Observer location not set: no airmass detrend column. Set it on the Target page.")

        val sb = StringBuilder()
        sb.append("#TYPE=EXOPLANET\n")
        sb.append("#OBSCODE=").append(clean(st.obsCode).uppercase()).append('\n')
        if (st.secondaryObsCodes.isNotBlank()) sb.append("#SECONDARY_OBSCODES=").append(Fmt.clip(st.secondaryObsCodes.replace(" ", ""), 60)).append('\n')
        sb.append("#SOFTWARE=").append(Fmt.clip(clean(software), 255)).append('\n')
        sb.append("#DELIM=,\n")
        sb.append("#DATE_TYPE=").append(sys.exoCode).append('\n')
        sb.append("#OBSTYPE=CCD\n")
        sb.append("#STAR_NAME=").append(Fmt.clip(clean(starName), 100)).append('\n')
        sb.append("#EXOPLANET_NAME=").append(Fmt.clip(clean(planet), 100)).append('\n')
        sb.append("#BINNING=").append(st.binning).append('\n')
        val exp = ds.exposureSec
        sb.append("#EXPOSURE_TIME=").append(if (exp.isFinite()) Fmt.f(exp, 1) else "na").append('\n')
        sb.append("#FILTER=").append(st.exoFilter).append('\n')
        sb.append("#DETREND_PARAMETERS=").append(if (hasAm) "AIRMASS" else "").append('\n')
        sb.append("#MEASUREMENT_TYPE=Rnflux\n")

        val priors = ArrayList<String>()
        tg.period?.let { priors.add("Period=" + Fmt.auto(it)) }
        tg.aRs?.let { priors.add("a/R*=" + Fmt.f(it, 3)) }
        tg.inclination?.let { priors.add("inc=" + Fmt.f(it, 2)) }
        tg.rpRs?.let { priors.add("Rp/R*=" + Fmt.f(it, 4)) }
        priors.add("u1=" + Fmt.f(tg.u1, 3)); priors.add("u2=" + Fmt.f(tg.u2, 3))
        sb.append("#PRIORS=").append(Fmt.clip(priors.joinToString(","), 250)).append('\n')

        if (r != null && d != null) {
            val res = ArrayList<String>()
            val ti = r.model.timingIndex
            if (ti != null) {
                var tc = r.params[ti] + d.tRef
                if (d.system != sys && session.hasCoords) tc += TimeSystems.offset(ds.firstJd, session.ra, session.dec, d.system, sys)
                res.add("Tc=" + Fmt.f(tc, 6) + "+/-" + Fmt.f(r.errors[ti], 6))
            }
            when (val m = r.model) {
                is LimbDarkenedTransitModel -> {
                    res.add("Rp/R*=" + Fmt.f(r.params[4], 4) + "+/-" + Fmt.f(r.errors[4], 4))
                    res.add("Depth=" + Fmt.f(r.params[4] * r.params[4], 5))
                    res.add("a/R*=" + Fmt.f(r.params[5], 3))
                    res.add("b=" + Fmt.f(r.params[6], 3))
                    res.add("T14=" + Fmt.f(m.t14(r.params) * 24, 3) + "h")
                }
                is TrapezoidTransitModel -> {
                    res.add("Depth=" + Fmt.f(r.params[4], 5) + "+/-" + Fmt.f(r.errors[4], 5))
                    res.add("T14=" + Fmt.f(r.params[5] * 24, 3) + "h")
                }
                else -> {}
            }
            res.add("model=" + r.model.id)
            sb.append("#RESULTS=").append(Fmt.clip(res.joinToString(","), 250)).append('\n')
        } else warn.add("No fit result: #RESULTS line omitted.")

        val notes = ArrayList<String>()
        notes.add("ZWO Seestar smart telescope (CMOS camera reported as CCD); ensemble magnitudes from the Seestar app converted to flux and normalised to the out-of-transit mean")
        if (st.exoNotes.isNotBlank()) notes.add(clean(st.exoNotes))
        sb.append("#NOTES=").append(notes.joinToString("; ")).append('\n')
        sb.append("# DATE,DIFF,MERR,DETREND_1\n")

        var rows = 0
        for ((i, o) in used.withIndex()) {
            val f = fluxes[i] / norm
            val e = errFor(o, scatter, st.errorSource)
            val fe = if (e.isFinite()) f * ln(10.0) / 2.5 * e else Double.NaN
            sb.append(Fmt.f(times[i], 6)).append(',')
                .append(Fmt.f(f, 6)).append(',')
                .append(if (fe.isFinite()) Fmt.f(fe, 6) else "na").append(',')
                .append(if (hasAm) Fmt.f(am[i], 6) else "na").append('\n')
            rows++
        }
        val name = "AAVSO_exoplanet_${planet.replace(' ', '_')}_${Fmt.f(ds.firstJd, 2)}.txt"
        return ExportResult(sb.toString(), warn, rows, name)
    }
}

object Report {
    fun fitReport(session: Session, r: FitResult, d: FitData, derived: List<Derived>, software: String): String {
        val ds = session.dataset
        val tg = session.target
        val sb = StringBuilder()
        sb.append("VarFit report — ").append(software).append('\n')
        sb.append("Object: ").append(tg.vsxName ?: tg.name)
        tg.vsxType?.let { sb.append(" (").append(it).append(')') }
        sb.append('\n')
        if (ds != null) {
            sb.append("Data: ").append(ds.sourceName).append(" — ").append(d.n).append(" points used of ").append(ds.obs.size).append('\n')
            if (session.hasCoords) sb.append("RA/Dec (J2000): ").append(Fmt.f(session.ra, 5)).append(", ").append(Fmt.f(session.dec, 5)).append('\n')
        }
        sb.append("Time system: ").append(d.system.label).append("; reference tRef = ").append(Fmt.f(d.tRef, 5)).append('\n')
        sb.append("Star class: ").append(tg.starClass.label).append('\n')
        if (tg.hasEphemeris()) sb.append("Ephemeris: E0 = ").append(Fmt.f(tg.epoch!!, 6)).append(" (").append(tg.epochSystem.label)
            .append("), P = ").append(Fmt.auto(tg.period!!)).append(" d\n")
        sb.append('\n')
        sb.append("Model: ").append(r.model.name).append('\n')
        sb.append("       ").append(r.model.formula).append('\n')
        sb.append("χ² = ").append(Fmt.f(r.chi2, 3)).append(", ndf = ").append(r.ndf)
            .append(", χ²/ndf = ").append(Fmt.f(r.chi2r, 4)).append(", BIC = ").append(Fmt.f(r.bic, 3))
            .append(", AIC = ").append(Fmt.f(r.aic, 3)).append('\n')
        sb.append("Status: ").append(r.message).append(" after ").append(r.iterations).append(" iterations")
        if (r.errorsScaled) sb.append("; errors scaled by √(χ²/ndf)")
        if (d.equalWeights) sb.append("; equal weights from data scatter")
        sb.append('\n').append('\n')
        sb.append("Parameters:\n")
        for ((i, ps) in r.model.params.withIndex()) {
            val v = if (ps.isTime) r.params[i] + d.tRef else r.params[i]
            val e = r.errors[i]
            sb.append("  ").append(ps.name.padEnd(8)).append(' ')
            sb.append(if (r.fixed[i]) Fmt.auto(v) + "  (fixed)" else Fmt.valueErr(v, e, 6))
            if (ps.unit.isNotEmpty()) sb.append(' ').append(ps.unit)
            if (ps.isTime) sb.append(' ').append(d.system.label)
            sb.append('\n')
        }
        if (r.atBound.isNotEmpty()) sb.append("  Warning: at bound: ").append(r.atBound.joinToString { r.model.params[it].name }).append('\n')
        sb.append('\n').append("Derived:\n")
        for (dv in derived) {
            sb.append("  ").append(dv.label).append(": ")
            if (dv.isTime) sb.append(Fmt.valueErr(dv.value, dv.err, 5)) else sb.append(Fmt.valueErr(dv.value, dv.err, 4))
            if (dv.unit.isNotEmpty()) sb.append(' ').append(dv.unit)
            if (dv.isTime && dv.err.isFinite()) sb.append("  (± ").append(Fmt.f(dv.err * 1440, 2)).append(" min)")
            if (dv.note.isNotEmpty()) sb.append("  [").append(dv.note).append(']')
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Ranking table after an automatic fit. */
    fun ranking(results: List<FitResult>): String {
        val sb = StringBuilder()
        sb.append("Rank  Model                                   χ²/ndf     BIC       ΔBIC\n")
        val best = results.firstOrNull()?.bic ?: 0.0
        for ((i, r) in results.withIndex()) {
            sb.append((i + 1).toString().padEnd(6))
                .append(Fmt.clip(r.model.name, 38).padEnd(40))
                .append(Fmt.f(r.chi2r, 3).padEnd(11))
                .append(Fmt.f(r.bic, 2).padEnd(10))
                .append(Fmt.f(r.bic - best, 2)).append('\n')
        }
        return sb.toString()
    }

    fun errorScale(r: FitResult) = max(1.0, kotlin.math.sqrt(r.chi2r))
}
