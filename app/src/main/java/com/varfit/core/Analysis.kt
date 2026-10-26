package com.varfit.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class Periodogram(val freq: DoubleArray, val power: DoubleArray) {
    val bestIndex: Int get() = power.indices.maxByOrNull { power[it] } ?: 0
    val bestFrequency: Double get() = freq[bestIndex]
    val bestPeriod: Double get() = 1.0 / bestFrequency
}

/** An extracted event time. [tRel] is relative to FitData.tRef; [err] in days. */
class EventTiming(val kind: EventKind, val tRel: Double, val err: Double, val method: String)

class Derived(val label: String, val value: Double, val err: Double, val unit: String, val isTime: Boolean = false, val note: String = "")

class KvwResult(val tRel: Double, val err: Double, val pairs: Int)

object Analysis {

    // ---------------------------------------------------------------- periodogram

    /** Generalised Lomb–Scargle periodogram (Zechmeister & Kürster 2009), normalised power 0…1. */
    fun periodogram(t: DoubleArray, y: DoubleArray, sigma: DoubleArray, fMin: Double, fMax: Double, nFreq: Int): Periodogram? {
        val n = t.size
        if (n < 5 || fMax <= fMin || nFreq < 2) return null
        val w = DoubleArray(n) { 1.0 / (sigma[it] * sigma[it]) }
        val ws = w.sum()
        for (i in 0 until n) w[i] /= ws
        var yMean = 0.0
        for (i in 0 until n) yMean += w[i] * y[i]
        var yy = 0.0
        for (i in 0 until n) yy += w[i] * (y[i] - yMean) * (y[i] - yMean)
        val freq = DoubleArray(nFreq) { fMin + (fMax - fMin) * it / (nFreq - 1).toDouble() }
        val pow = DoubleArray(nFreq)
        for ((k, f) in freq.withIndex()) {
            val om = 2 * PI * f
            var c = 0.0; var s = 0.0; var yc = 0.0; var ys = 0.0; var cc = 0.0; var ss = 0.0; var cs = 0.0
            for (i in 0 until n) {
                val co = cos(om * t[i]); val si = sin(om * t[i])
                val dy = y[i] - yMean
                c += w[i] * co; s += w[i] * si
                yc += w[i] * dy * co; ys += w[i] * dy * si
                cc += w[i] * co * co; ss += w[i] * si * si; cs += w[i] * co * si
            }
            val ccv = cc - c * c
            val ssv = ss - s * s
            val csv = cs - c * s
            val d = ccv * ssv - csv * csv
            pow[k] = if (d > 0 && yy > 0) ((ssv * yc * yc + ccv * ys * ys - 2 * csv * yc * ys) / (yy * d)).coerceIn(0.0, 1.0) else 0.0
        }
        return Periodogram(freq, pow)
    }

    // ---------------------------------------------------------------- extrema

    /** Interior extremum of the model in [a, b] (brightest for MAX, faintest otherwise), or null if at an edge. */
    fun findExtremum(model: FitModel, p: DoubleArray, a: Double, b: Double, kind: EventKind, x: Double = 0.0): Double? {
        val n = 600
        val sign = if (kind == EventKind.MAX) 1.0 else -1.0   // minimise sign*mag
        var bestI = 0
        var bestV = Double.MAX_VALUE
        val dt = (b - a) / (n - 1)
        for (i in 0 until n) {
            val v = sign * model.value(a + i * dt, x, p)
            if (v < bestV) { bestV = v; bestI = i }
        }
        if (bestI == 0 || bestI == n - 1) return null
        var lo = a + (bestI - 1) * dt
        var hi = a + (bestI + 1) * dt
        val g = (sqrt(5.0) - 1) / 2
        repeat(60) {
            val m1 = hi - g * (hi - lo)
            val m2 = lo + g * (hi - lo)
            if (sign * model.value(m1, x, p) < sign * model.value(m2, x, p)) hi = m2 else lo = m1
        }
        return 0.5 * (lo + hi)
    }

    /** Monte-Carlo propagation of the fit covariance to a derived quantity. Returns (mean, sd) or null. */
    fun monteCarlo(r: FitResult, samples: Int, seed: Long = 42, f: (DoubleArray) -> Double?): Pair<Double, Double>? {
        val free = r.params.indices.filter { !r.fixed[it] }
        if (free.isEmpty()) return null
        val c = Array(free.size) { u -> DoubleArray(free.size) { v -> r.cov[free[u]][free[v]] } }
        if (c.any { row -> row.any { !it.isFinite() } }) return null
        var l = Linalg.cholesky(c)
        if (l == null) {
            for (u in free.indices) c[u][u] = c[u][u] * (1 + 1e-6) + 1e-30
            l = Linalg.cholesky(c) ?: return null
        }
        val rnd = Random(seed)
        val vals = ArrayList<Double>()
        val z = DoubleArray(free.size)
        repeat(samples) {
            for (u in z.indices) z[u] = rnd.nextGaussian()
            val p = r.params.copyOf()
            for (u in free.indices) {
                var s = 0.0
                for (v in 0..u) s += l[u][v] * z[v]
                p[free[u]] += s
            }
            for (k in p.indices) p[k] = p[k].coerceIn(r.lower[k], r.upper[k])
            f(p)?.let { if (it.isFinite()) vals.add(it) }
        }
        if (vals.size < samples / 4) return null
        val mean = vals.average()
        val sd = sqrt(vals.sumOf { (it - mean) * (it - mean) } / (vals.size - 1))
        return Pair(mean, sd)
    }

    /** Event time from a fit: directly from the timing parameter, or numerically with Monte-Carlo errors. */
    fun eventTime(r: FitResult, d: FitData, requested: EventKind): EventTiming? {
        val model = r.model
        val kind = if (requested == EventKind.AUTO) FitContext.resolveEvent(requested, d) else requested
        val ti = model.timingIndex
        if (ti != null) {
            val ev = model.timingEvent(r.params, kind)
            val err = if (r.fixed[ti]) Double.NaN else r.errors[ti]
            return EventTiming(ev, r.params[ti], err, "fit parameter ${model.params[ti].name}")
        }
        if (model is LineModel) return null
        val searchKind = if (kind == EventKind.TRANSIT) EventKind.MIN else kind
        val a = d.tMin
        val b = d.tMax
        val t = findExtremum(model, r.params, a, b, searchKind) ?: return null
        // window around the found extremum keeps MC samples on the same extremum
        val half = if (model is FourierModel) 0.3 * r.params[2] else (b - a) / 2
        val mc = monteCarlo(r, 300) { p -> findExtremum(model, p, max(a - half, t - half), min(b + half, t + half), searchKind) }
        return EventTiming(searchKind, t, mc?.second ?: Double.NaN, "model extremum (MC errors)")
    }

    // ---------------------------------------------------------------- derived quantities

    fun derive(r: FitResult, d: FitData, target: TargetInfo, epochRel: Double?, requested: EventKind): List<Derived> {
        val out = ArrayList<Derived>()
        val model = r.model
        val p = r.params
        val ev = eventTime(r, d, requested)
        if (ev != null) {
            out.add(Derived(ev.kind.label, ev.tRel + d.tRef, ev.err, d.system.label, isTime = true, note = ev.method))
            out.add(Derived("Magnitude at event", model.value(ev.tRel, 0.0, p), Double.NaN, "mag"))
            val per = target.period
            val matches = when (target.starClass.defaultEvent) {
                EventKind.MAX -> ev.kind == EventKind.MAX
                EventKind.MIN -> ev.kind == EventKind.MIN
                EventKind.TRANSIT -> true
                EventKind.AUTO -> false
            }
            if (per != null && epochRel != null && matches) {
                val (cyc, calc, oc) = Ephemeris.oMinusC(ev.tRel, epochRel, per)
                out.add(Derived("Cycle number E", cyc.toDouble(), Double.NaN, ""))
                out.add(Derived("Calculated (ephemeris)", calc + d.tRef, Double.NaN, d.system.label, isTime = true))
                out.add(Derived("O − C", oc * 1440.0, ev.err * 1440.0, "min"))
            }
        } else if (model !is LineModel) {
            out.add(Derived("Event time", Double.NaN, Double.NaN, "", note = "no extremum inside the data window"))
        }

        when (model) {
            is FourierModel -> {
                out.add(Derived("Mean magnitude", p[0], r.errors[0], "mag"))
                val amp = { q: DoubleArray -> peakToPeak(model, q, q[1], q[1] + q[2]) }
                val mc = monteCarlo(r, 200, 7) { amp(it) }
                out.add(Derived("Amplitude (peak-to-peak)", amp(p), mc?.second ?: Double.NaN, "mag"))
                if (!r.fixed[2]) out.add(Derived("Period", p[2], r.errors[2], "d"))
                val cover = d.span / p[2]
                out.add(Derived("Phase coverage", cover * 100, Double.NaN, "%", note = if (cover < 1) "less than one cycle: shape and amplitude depend on the catalog period" else ""))
            }
            is PolyExtremumModel -> {
                out.add(Derived("Curvature c2", p[2], r.errors[2], "mag", note = "per (span/2)²"))
            }
            is GaussianModel -> {
                out.add(Derived("Amplitude A", p[3], r.errors[3], "mag"))
                out.add(Derived("FWHM", 2.3548 * p[4] * 24, 2.3548 * r.errors[4] * 24, "h"))
            }
            is MikulasekModel -> {
                out.add(Derived("Depth A", p[3], r.errors[3], "mag"))
                out.add(Derived("Width D", p[4] * 24, r.errors[4] * 24, "h"))
            }
            is TrapezoidTransitModel -> {
                out.add(Derived("Depth", p[4] * 100, r.errors[4] * 100, "%"))
                out.add(Derived("Rp/R* (≈√depth)", sqrt(max(p[4], 0.0)), if (p[4] > 0) r.errors[4] / (2 * sqrt(p[4])) else Double.NaN, ""))
                out.add(Derived("Duration T14", p[5] * 24, r.errors[5] * 24, "h"))
                out.add(Derived("Ingress T12", min(p[6], p[5] / 2) * 24, r.errors[6] * 24, "h"))
            }
            is LimbDarkenedTransitModel -> {
                out.add(Derived("Rp/R*", p[4], r.errors[4], ""))
                out.add(Derived("Depth (Rp/R*)²", p[4] * p[4] * 100, 2 * p[4] * r.errors[4] * 100, "%"))
                val mcT = monteCarlo(r, 200, 11) { model.t14(it) }
                out.add(Derived("Duration T14", model.t14(p) * 24, (mcT?.second ?: Double.NaN) * 24, "h"))
                out.add(Derived("Inclination", model.inclinationDeg(p), Double.NaN, "°"))
                if (!r.fixed[5]) out.add(Derived("a/R*", p[5], r.errors[5], ""))
                if (!r.fixed[6]) out.add(Derived("Impact parameter b", p[6], r.errors[6], ""))
            }
            else -> {}
        }
        if (model.isTransitModel && !r.fixed[2]) out.add(Derived("Airmass coefficient", p[2], r.errors[2], "mag/airmass"))
        out.add(Derived("Residual rms", r.residualRms * 1000, Double.NaN, "mmag"))
        return out
    }

    fun peakToPeak(model: FitModel, p: DoubleArray, a: Double, b: Double, n: Int = 500): Double {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0 until n) {
            val v = model.value(a + (b - a) * i / (n - 1).toDouble(), 0.0, p)
            lo = min(lo, v); hi = max(hi, v)
        }
        return hi - lo
    }

    // ---------------------------------------------------------------- Kwee–van Woerden

    /**
     * Kwee & van Woerden (1956) time of a symmetric extremum. Data are interpolated to a uniform grid,
     * reflected about trial times, and the sum of squared differences is fitted with a parabola.
     */
    fun kweeVanWoerden(tIn: DoubleArray, yIn: DoubleArray): KvwResult? {
        val idx = tIn.indices.sortedBy { tIn[it] }
        val t = DoubleArray(idx.size) { tIn[idx[it]] }
        val y = DoubleArray(idx.size) { yIn[idx[it]] }
        val n = t.size
        if (n < 8) return null
        val t0 = t.first()
        val t1 = t.last()
        val dt = (t1 - t0) / (n - 1)
        if (dt <= 0) return null
        val grid = DoubleArray(n) { t0 + it * dt }
        val g = DoubleArray(n) { interp(t, y, grid[it]) }

        fun s(tt: Double): Pair<Double, Int> {
            var sum = 0.0
            var cnt = 0
            for (k in 0 until n) {
                if (grid[k] <= tt) continue
                val refl = 2 * tt - grid[k]
                if (refl < t0) continue
                val d = g[k] - interp(grid, g, refl)
                sum += d * d
                cnt++
            }
            return Pair(sum, cnt)
        }

        // coarse search for the symmetry point within the central 70 %
        var bestT = Double.NaN
        var bestS = Double.MAX_VALUE
        val a = t0 + 0.15 * (t1 - t0)
        val b = t1 - 0.15 * (t1 - t0)
        var tt = a
        while (tt <= b) {
            val (sum, cnt) = s(tt)
            if (cnt >= 3) {
                val mean = sum / cnt
                if (mean < bestS) { bestS = mean; bestT = tt }
            }
            tt += dt / 4
        }
        if (bestT.isNaN()) return null
        val (sm, cm) = s(bestT - dt)
        val (s0, c0) = s(bestT)
        val (sp, cp) = s(bestT + dt)
        if (c0 < 3) return null
        // normalise to the pair count at the centre so the parabola is not distorted by changing counts
        val ym = sm / max(cm, 1) * c0
        val y0 = s0
        val yp = sp / max(cp, 1) * c0
        val aa = (ym - 2 * y0 + yp) / (2 * dt * dt)
        if (aa <= 0) return null
        val bb = (yp - ym) / (2 * dt) - 2 * aa * bestT
        val cc = y0 - aa * bestT * bestT - bb * bestT
        val tMin = -bb / (2 * aa)
        val z = max(c0 / 2.0, 2.0)
        val varT = (4 * aa * cc - bb * bb) / (4 * aa * aa * (z - 1))
        return KvwResult(tMin, if (varT > 0) sqrt(varT) else Double.NaN, c0)
    }

    private fun interp(x: DoubleArray, y: DoubleArray, v: Double): Double {
        if (v <= x.first()) return y.first()
        if (v >= x.last()) return y.last()
        var lo = 0
        var hi = x.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (x[m] <= v) lo = m else hi = m }
        val f = (v - x[lo]) / (x[hi] - x[lo])
        return y[lo] + f * (y[hi] - y[lo])
    }

    fun isClose(a: Double, b: Double, tol: Double) = abs(a - b) <= tol
}
