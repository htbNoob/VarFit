package com.varfit.app

import android.widget.LinearLayout
import com.varfit.core.Ephemeris
import com.varfit.core.EventKind
import com.varfit.core.Fmt
import com.varfit.core.LimbDarkening
import com.varfit.core.StarClass
import com.varfit.core.TimeSystem

fun MainActivity.buildTargetPage(root: LinearLayout) {
    val st = AppState
    val s = st.session
    val t = s.target

    // ------------------------------------------------------------ object & class
    val c1 = card(heading("Target"))
    val fName = field("Name (used for catalog search by name)", t.name)
    val fRa = field("RA [deg, J2000]", if (s.ra.isFinite()) Fmt.f(s.ra, 6) else "", numeric = true)
    val fDec = field("Dec [deg, J2000]", if (s.dec.isFinite()) Fmt.f(s.dec, 6) else "", numeric = true)
    c1.addView(fName.view)
    c1.addView(columns(fRa.view, fDec.view))
    val classes = StarClass.values().toList()
    c1.addView(labeledSpinner("What kind of variable do you expect?", classes.map { it.label }, classes.indexOf(t.starClass)) { i ->
        commit()
        t.starClass = classes[i]
        st.eventKind = t.starClass.defaultEvent
        s.timeSystem = t.starClass.defaultTimeSystem
        st.modelId = null
        st.resetFits()
        commit = {}
        refresh()
    })
    val events = EventKind.values().toList()
    c1.addView(labeledSpinner("Event to time", events.map { it.label }, events.indexOf(st.eventKind)) { i ->
        st.eventKind = events[i]; st.result = null
    })
    val systems = TimeSystem.values().toList()
    c1.addView(labeledSpinner("Time system for fitting and results", systems.map { it.label }, systems.indexOf(s.timeSystem)) { i ->
        commit()
        s.timeSystem = systems[i]
        st.resetFits()
        commit = {}
        refresh()
    })
    c1.addView(note("HJD (UTC) is the usual choice for variable-star timings (AAVSO, BAV); BJD (TDB) is required for exoplanet transits."))
    root.addView(c1)

    // ------------------------------------------------------------ catalogs
    val c2 = card(heading("Catalog lookup"))
    t.catalogNote?.let { c2.addView(note(it, Pal.good)) }
    t.vsxType?.let { c2.addView(note("VSX type: $it" + (t.magRange?.let { r -> " · range $r" } ?: ""))) }
    val fRad = field("Search radius [arcsec]", "60", numeric = true)
    c2.addView(fRad.view)
    c2.addView(buttonRows(2,
        button("VSX near position", primary = true) { lookupVsx(byName = false, radiusArcsec = fRad.number ?: 60.0) },
        button("VSX by name") { lookupVsx(byName = true, radiusArcsec = 0.0) },
        button("Exoplanets near position") { lookupExo(byName = false, radiusArcsec = fRad.number ?: 60.0) },
        button("Exoplanets by name") { lookupExo(byName = true, radiusArcsec = 0.0) }
    ))
    c2.addView(note("VSX (AAVSO International Variable Star Index) fills type, period and epoch. The NASA Exoplanet Archive fills period, mid-transit time, depth, duration, a/R*, inclination and limb darkening. Everything stays editable below."))
    root.addView(c2)

    // ------------------------------------------------------------ ephemeris
    val c3 = card(heading("Ephemeris"))
    val fPer = field("Period [d]", t.period?.let { Fmt.full(it) } ?: "", numeric = true)
    val fEp = field(if (t.starClass == StarClass.TRANSIT) "Epoch: mid-transit Tc" else if (t.starClass == StarClass.ECL) "Epoch of minimum" else "Epoch of maximum",
        t.epoch?.let { Fmt.full(it) } ?: "", numeric = true)
    c3.addView(columns(fPer.view, fEp.view))
    c3.addView(labeledSpinner("Time system of the epoch", systems.map { it.label }, systems.indexOf(t.epochSystem)) { i ->
        t.epochSystem = systems[i]; st.resetModelParams()
    })
    val ds = s.dataset
    if (ds != null && t.hasEphemeris()) {
        val a = s.time(ds.obs.first()); val b = s.time(ds.obs.last())
        val ep = s.epochInFitSystem()!!
        val ev = Ephemeris.eventsIn(a - 0.02, b + 0.02, ep, t.period!!, 10)
        val txt = if (ev.isEmpty()) {
            val next = Ephemeris.calculated(b, ep, t.period!!).let { if (it < b) it + t.period!! else it }
            "No predicted event during your observation. Next: ${Fmt.f(next, 5)} (${s.timeSystem.label}), " +
                "${Fmt.f((next - b) * 24, 1)} h after the last point."
        } else "Predicted during your data (${s.timeSystem.label}):\n" + ev.joinToString("\n") {
            "  E = ${Ephemeris.cycle(it, ep, t.period!!)}: ${Fmt.f(it, 5)}"
        }
        c3.addView(note(txt))
        if (s.timeSystem != t.epochSystem) c3.addView(note("The epoch is converted from ${t.epochSystem.label} to ${s.timeSystem.label} automatically."))
    }
    root.addView(c3)

    // ------------------------------------------------------------ transit parameters
    var transitFields: List<Field> = emptyList()
    if (t.starClass == StarClass.TRANSIT) {
        val c4 = card(heading("Transit parameters (start values / priors)"))
        val fK = field("Rp/R*", t.rpRs?.let { Fmt.f(it, 4) } ?: "", numeric = true)
        val fA = field("a/R*", t.aRs?.let { Fmt.f(it, 3) } ?: "", numeric = true)
        val fInc = field("Inclination [°]", t.inclination?.let { Fmt.f(it, 2) } ?: "", numeric = true)
        val fB = field("Impact parameter b", t.impact?.let { Fmt.f(it, 3) } ?: "", numeric = true)
        val fT14 = field("Duration T14 [h]", t.t14Hours?.let { Fmt.f(it, 3) } ?: "", numeric = true)
        val fDep = field("Depth [%]", t.depthPct?.let { Fmt.f(it, 3) } ?: "", numeric = true)
        val fTeff = field("Host Teff [K]", t.teff?.let { Fmt.f(it, 0) } ?: "", numeric = true)
        val fU1 = field("Limb darkening u1", Fmt.f(t.u1, 3), numeric = true)
        val fU2 = field("u2", Fmt.f(t.u2, 3), numeric = true)
        c4.addView(columns(fK.view, fA.view))
        c4.addView(columns(fInc.view, fB.view))
        c4.addView(columns(fT14.view, fDep.view))
        c4.addView(columns(fTeff.view, fU1.view, fU2.view))
        c4.addView(button("Limb darkening from Teff") {
            commit()
            val te = t.teff
            if (te == null) toast("Enter Teff first") else {
                val (u1, u2) = LimbDarkening.forTeff(te); t.u1 = u1; t.u2 = u2; commit = {}; refresh()
            }
        })
        c4.addView(note("Limb-darkening values are approximate (V band, quadratic law); they barely affect the transit time. a/R* and b are fixed to these values by default because a single small-telescope light curve constrains them poorly."))
        transitFields = listOf(fK, fA, fInc, fB, fT14, fDep, fTeff, fU1, fU2)
        root.addView(c4)
    }

    // ------------------------------------------------------------ observer site
    val c5 = card(heading("Observer location (for airmass)"))
    val fLat = field("Latitude [°, north +]", s.site.lat?.let { Fmt.f(it, 4) } ?: "", numeric = true)
    val fLon = field("Longitude [°, east +]", s.site.lon?.let { Fmt.f(it, 4) } ?: "", numeric = true)
    c5.addView(columns(fLat.view, fLon.view))
    c5.addView(note("Optional. Enables the airmass column in AAVSO files and the airmass term kX in transit fits. Stored on this device only."))
    root.addView(c5)

    commit = {
        t.name = fName.text.trim()
        fun override(newV: Double?, fileV: Double?): Double? = when {
            newV == null -> null
            fileV != null && fileV.isFinite() && kotlin.math.abs(fileV - newV) < 2e-6 -> null
            else -> newV
        }
        val oldRa = s.ra; val oldDec = s.dec
        s.raOverride = override(fRa.number, s.dataset?.ra)
        s.decOverride = override(fDec.number, s.dataset?.dec)
        if (!(oldRa == s.ra || (oldRa.isNaN() && s.ra.isNaN())) || !(oldDec == s.dec || (oldDec.isNaN() && s.dec.isNaN()))) st.resetFits()
        val oldP = t.period; val oldE = t.epoch
        t.period = fPer.number?.takeIf { it > 0 }
        t.epoch = fEp.number
        if (oldP != t.period || oldE != t.epoch) st.resetModelParams()
        if (transitFields.isNotEmpty()) {
            val (fK, fA, fInc, fB) = transitFields
            val fT14 = transitFields[4]; val fDep = transitFields[5]; val fTeff = transitFields[6]
            val fU1 = transitFields[7]; val fU2 = transitFields[8]
            val before = listOf(t.rpRs, t.aRs, t.inclination, t.impact, t.t14Hours, t.depthPct)
            t.rpRs = fK.number; t.aRs = fA.number; t.inclination = fInc.number; t.impact = fB.number
            t.t14Hours = fT14.number; t.depthPct = fDep.number; t.teff = fTeff.number
            fU1.number?.let { t.u1 = it }; fU2.number?.let { t.u2 = it }
            if (before != listOf(t.rpRs, t.aRs, t.inclination, t.impact, t.t14Hours, t.depthPct)) st.resetModelParams()
        }
        s.site.lat = fLat.number?.takeIf { it in -90.0..90.0 }
        s.site.lon = fLon.number?.let { if (it > 180) it - 360 else it }?.takeIf { it in -180.0..180.0 }
    }
}

private fun MainActivity.lookupVsx(byName: Boolean, radiusArcsec: Double) {
    val s = AppState.session
    commit()
    val name = s.target.name.ifBlank { s.dataset?.name ?: "" }
    if (!byName && !s.hasCoords) { toast("No coordinates: enter RA/Dec or search by name"); return }
    if (byName && name.isBlank()) { toast("Enter a name"); return }
    val ra = s.ra.takeIf { it.isFinite() }; val dec = s.dec.takeIf { it.isFinite() }
    runTask("Querying AAVSO VSX…", { _, _ ->
        if (byName) Catalogs.vsxByName(name, ra, dec) else Catalogs.vsxCone(ra!!, dec!!, radiusArcsec / 3600.0)
    }) { list ->
        if (list.isEmpty()) {
            message("VSX", "No variable star found" + if (!byName) " within ${Fmt.f(radiusArcsec, 0)}\". Try a larger radius or search by name." else ".")
        } else {
            choose("VSX results", list.map { it.summary() }) { i ->
                list[i].applyTo(s.target)
                AppState.eventKind = s.target.starClass.defaultEvent
                s.timeSystem = s.target.starClass.defaultTimeSystem
                AppState.resetFits()
                AppState.modelId = null
                commit = {}
                refresh()
                toast("Applied ${list[i].name}")
            }
        }
    }
}

private fun MainActivity.lookupExo(byName: Boolean, radiusArcsec: Double) {
    val s = AppState.session
    commit()
    val name = s.target.name.ifBlank { s.dataset?.name ?: "" }
    if (!byName && !s.hasCoords) { toast("No coordinates: enter RA/Dec or search by name"); return }
    if (byName && name.isBlank()) { toast("Enter a name"); return }
    val ra = s.ra.takeIf { it.isFinite() }; val dec = s.dec.takeIf { it.isFinite() }
    runTask("Querying NASA Exoplanet Archive…", { _, _ ->
        if (byName) Catalogs.exoByName(name, ra, dec) else Catalogs.exoCone(ra!!, dec!!, radiusArcsec / 3600.0)
    }) { list ->
        if (list.isEmpty()) {
            message("NASA Exoplanet Archive", "No known planet found" + if (!byName) " within ${Fmt.f(radiusArcsec, 0)}\"." else ".")
        } else {
            choose("Planets", list.map { it.summary() }) { i ->
                list[i].applyTo(s.target)
                AppState.eventKind = EventKind.TRANSIT
                s.timeSystem = TimeSystem.BJD_TDB
                AppState.resetFits()
                AppState.modelId = null
                commit = {}
                refresh()
                toast("Applied ${list[i].planet}")
            }
        }
    }
}
