package com.varfit.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** χ² evaluated on a grid of two parameters. chi2[j][i] belongs to (xs[i], ys[j]). */
class Chi2MapResult(
    val model: FitModel,
    val ix: Int,
    val iy: Int,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val chi2: Array<DoubleArray>,
    val minChi2: Double,
    val bestI: Int,
    val bestJ: Int,
    val bestParams: DoubleArray,
    val profile: Boolean,
    /** χ²ᵣ used to scale Δχ² contours (1 if errors are trusted) */
    val chi2rScale: Double
) {
    val bestX get() = xs[bestI]
    val bestY get() = ys[bestJ]

    companion object {
        /** Δχ² for 1σ, 2σ, 3σ joint confidence regions of two parameters. */
        val DELTA_LEVELS = doubleArrayOf(2.30, 6.18, 11.83)
    }
}

object Chi2Mapper {

    /**
     * Suggested scan range for parameter k. With [wide] the range covers the plausible parameter space
     * (used for the global-minimum search); otherwise ±5σ around the current value.
     */
    fun defaultRange(model: FitModel, k: Int, p: DoubleArray, err: DoubleArray?, d: FitData,
                     lo: DoubleArray, hi: DoubleArray, wide: Boolean): Pair<Double, Double> {
        val spec = model.params[k]
        val e = err?.getOrNull(k) ?: Double.NaN
        var a: Double
        var b: Double
        if (!wide && e.isFinite() && e > 0) {
            a = p[k] - 5 * e; b = p[k] + 5 * e
        } else if (spec.isTime) {
            if (model is FourierModel) {
                val per = p[2]
                a = p[k] - per / 2; b = p[k] + per / 2
            } else {
                a = d.tMin; b = d.tMax
            }
        } else if (spec.name == "P") {
            a = p[k] * 0.9; b = p[k] * 1.1
        } else {
            val half = max(0.6 * abs(p[k]), 200 * spec.step)
            a = p[k] - half; b = p[k] + half
        }
        a = max(a, lo[k]); b = min(b, hi[k])
        if (b <= a) { a = p[k] - 100 * spec.step; b = p[k] + 100 * spec.step }
        return Pair(a, b)
    }

    fun run(
        model: FitModel,
        d: FitData,
        base: DoubleArray,
        fixed: BooleanArray,
        lower: DoubleArray,
        upper: DoubleArray,
        ix: Int,
        iy: Int,
        xRange: Pair<Double, Double>,
        yRange: Pair<Double, Double>,
        nx: Int,
        ny: Int,
        profile: Boolean,
        chi2rScale: Double = 1.0,
        cancel: () -> Boolean = { false },
        progress: (Double) -> Unit = {}
    ): Chi2MapResult? {
        require(ix != iy)
        val xs = DoubleArray(nx) { xRange.first + (xRange.second - xRange.first) * it / (nx - 1).toDouble() }
        val ys = DoubleArray(ny) { yRange.first + (yRange.second - yRange.first) * it / (ny - 1).toDouble() }
        val chi = Array(ny) { DoubleArray(nx) { Double.NaN } }
        val paramsAt = Array(ny) { arrayOfNulls<DoubleArray>(nx) }
        val innerFixed = fixed.copyOf().also { it[ix] = true; it[iy] = true }
        val anyFree = innerFixed.any { !it }
        var best = Double.MAX_VALUE
        var bi = 0
        var bj = 0
        var bestP = base.copyOf()
        var count = 0
        val total = nx * ny
        var prev = base.copyOf()
        for (j in 0 until ny) {
            val order = if (j % 2 == 0) (0 until nx) else (nx - 1 downTo 0)
            for (i in order) {
                if (cancel()) return null
                val start = (if (j > 0) paramsAt[j - 1][i] else null) ?: prev
                val p = start.copyOf()
                p[ix] = xs[i]; p[iy] = ys[j]
                val c: Double
                val pp: DoubleArray
                if (profile && anyFree) {
                    val r = Fitter.fit(model, d, p, innerFixed, lower, upper, false, maxIter = 30, computeCovariance = false, cancel = cancel, forwardDiff = true)
                    pp = r.params
                    // normalize() may shift periodic T0; keep the grid value for the map
                    pp[ix] = xs[i]; pp[iy] = ys[j]
                    c = Fitter.chi2(model, d, pp)
                } else {
                    pp = p
                    c = Fitter.chi2(model, d, p)
                }
                chi[j][i] = c
                paramsAt[j][i] = pp
                prev = pp
                if (c < best) { best = c; bi = i; bj = j; bestP = pp.copyOf() }
                count++
                if (count % 5 == 0) progress(count.toDouble() / total)
            }
        }
        progress(1.0)
        return Chi2MapResult(model, ix, iy, xs, ys, chi, best, bi, bj, bestP, profile, chi2rScale)
    }
}

class AutoFitOutcome(val results: List<FitResult>, val failures: List<Pair<String, String>>)

/** Fits every applicable model, each with a 2D χ² grid search for the global minimum, and ranks them. */
object AutoFit {
    fun run(
        models: List<FitModel>,
        ctx: FitContext,
        scaleErrors: Boolean,
        grid: Int,
        cancel: () -> Boolean = { false },
        progress: (String, Double) -> Unit = { _, _ -> }
    ): AutoFitOutcome {
        val out = ArrayList<FitResult>()
        val fails = ArrayList<Pair<String, String>>()
        val d = ctx.data
        for ((mi, model) in models.withIndex()) {
            if (cancel()) break
            try {
                val p0 = model.initial(ctx)
                val fixed = model.defaultFixed(ctx)
                val lo = model.lower(ctx)
                val hi = model.upper(ctx)
                val r1 = Fitter.fit(model, d, p0, fixed, lo, hi, scaleErrors, cancel = cancel)
                var best = r1
                val (ix, iy) = model.mapPair(fixed)
                if (ix != iy && !(fixed[ix] && fixed[iy])) {
                    val g = if (model is LimbDarkenedTransitModel || model is MikulasekModel) min(grid, 13) else grid
                    val xr = Chi2Mapper.defaultRange(model, ix, r1.params, null, d, lo, hi, wide = true)
                    val yr = Chi2Mapper.defaultRange(model, iy, r1.params, null, d, lo, hi, wide = true)
                    val map = Chi2Mapper.run(model, d, r1.params, fixed, lo, hi, ix, iy, xr, yr, g, g, profile = true, cancel = cancel) { f ->
                        progress(model.name, (mi + f) / models.size)
                    }
                    if (map != null) {
                        val r2 = Fitter.fit(model, d, map.bestParams, fixed, lo, hi, scaleErrors, cancel = cancel)
                        if (r2.chi2 < best.chi2 - 1e-9) best = r2
                    }
                }
                out.add(best)
            } catch (e: Exception) {
                fails.add(Pair(model.name, e.message ?: e.javaClass.simpleName))
            }
            progress(model.name, (mi + 1.0) / models.size)
        }
        return AutoFitOutcome(out.sortedBy { it.bic }, fails)
    }
}
