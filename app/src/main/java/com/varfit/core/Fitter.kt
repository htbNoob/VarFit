package com.varfit.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

class FitResult(
    val model: FitModel,
    val params: DoubleArray,
    /** 1σ errors (scaled by √χ²ᵣ if [errorsScaled]); 0 for fixed parameters, NaN if undetermined */
    val errors: DoubleArray,
    val fixed: BooleanArray,
    /** full covariance matrix (already scaled like [errors]); rows/cols of fixed params are 0 */
    val cov: Array<DoubleArray>,
    val chi2: Double,
    val n: Int,
    val nFree: Int,
    val converged: Boolean,
    val iterations: Int,
    val message: String,
    val errorsScaled: Boolean,
    val residualRms: Double,
    val lower: DoubleArray,
    val upper: DoubleArray
) {
    val ndf get() = n - nFree
    val chi2r get() = if (ndf > 0) chi2 / ndf else Double.NaN
    /** Bayesian information criterion: χ² + k·ln n (lower is better, penalises extra parameters). */
    val bic get() = chi2 + nFree * ln(n.toDouble())
    val aic get() = chi2 + 2.0 * nFree
    val atBound: List<Int>
        get() = params.indices.filter { !fixed[it] && (abs(params[it] - lower[it]) < 1e-12 * max(1.0, abs(lower[it])) || abs(params[it] - upper[it]) < 1e-12 * max(1.0, abs(upper[it]))) }
}

/** Levenberg–Marquardt least squares with fixed parameters and box bounds. */
object Fitter {

    fun chi2(model: FitModel, d: FitData, p: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until d.n) {
            val r = (d.y[i] - model.value(d.t[i], d.x[i], p)) / d.sigma[i]
            s += r * r
        }
        return if (s.isFinite()) s else Double.MAX_VALUE
    }

    fun residualRms(model: FitModel, d: FitData, p: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until d.n) { val r = d.y[i] - model.value(d.t[i], d.x[i], p); s += r * r }
        return sqrt(s / d.n)
    }

    private fun clamp(p: DoubleArray, lo: DoubleArray, hi: DoubleArray) {
        for (i in p.indices) p[i] = p[i].coerceIn(lo[i], hi[i])
    }

    /** Weighted Jacobian (∂f/∂p / σ) for free parameters, central differences. */
    private fun jacobian(model: FitModel, d: FitData, p: DoubleArray, free: IntArray, lo: DoubleArray, hi: DoubleArray,
                         forward: Boolean = false): Array<DoubleArray> {
        val jac = Array(d.n) { DoubleArray(free.size) }
        val pp = p.copyOf()
        val f0 = if (forward) DoubleArray(d.n) { model.value(d.t[it], d.x[it], p) } else null
        for ((j, k) in free.withIndex()) {
            if (f0 != null) {
                var h = max(model.params[k].step, 1e-8 * abs(p[k]))
                if (p[k] + h > hi[k]) h = -h
                pp[k] = p[k] + h
                for (i in 0 until d.n) jac[i][j] = (model.value(d.t[i], d.x[i], pp) - f0[i]) / h / d.sigma[i]
                pp[k] = p[k]
                continue
            }
            val h = max(model.params[k].step, 1e-8 * abs(p[k]))
            var plus = p[k] + h
            var minus = p[k] - h
            if (plus > hi[k]) plus = p[k]
            if (minus < lo[k]) minus = p[k]
            if (plus == minus) { plus = p[k] + h; minus = p[k] }
            val dh = plus - minus
            for (i in 0 until d.n) {
                pp[k] = plus
                val fp = model.value(d.t[i], d.x[i], pp)
                pp[k] = minus
                val fm = model.value(d.t[i], d.x[i], pp)
                jac[i][j] = (fp - fm) / dh / d.sigma[i]
            }
            pp[k] = p[k]
        }
        return jac
    }

    fun fit(
        model: FitModel,
        d: FitData,
        p0: DoubleArray,
        fixed: BooleanArray,
        lower: DoubleArray,
        upper: DoubleArray,
        scaleErrors: Boolean,
        maxIter: Int = 300,
        computeCovariance: Boolean = true,
        cancel: () -> Boolean = { false },
        forwardDiff: Boolean = false
    ): FitResult {
        val p = p0.copyOf()
        clamp(p, lower, upper)
        val free = p.indices.filter { !fixed[it] }.toIntArray()
        val m = free.size
        var chi = chi2(model, d, p)
        var lambda = 1e-3
        var iter = 0
        var converged = false
        var message = ""
        var smallSteps = 0

        if (m == 0) {
            converged = true
            message = "all parameters fixed"
        }
        while (m > 0 && iter < maxIter && !cancel()) {
            iter++
            val jac = jacobian(model, d, p, free, lower, upper, forwardDiff)
            val a = Array(m) { DoubleArray(m) }
            val g = DoubleArray(m)
            for (i in 0 until d.n) {
                val r = (d.y[i] - model.value(d.t[i], d.x[i], p)) / d.sigma[i]
                val row = jac[i]
                for (u in 0 until m) {
                    g[u] += row[u] * r
                    for (v in 0..u) a[u][v] += row[u] * row[v]
                }
            }
            for (u in 0 until m) for (v in u + 1 until m) a[u][v] = a[v][u]
            var dmax = 0.0
            for (u in 0 until m) dmax = max(dmax, a[u][u])
            var accepted = false
            var newChi = chi
            var trial = p
            while (lambda < 1e12) {
                val aa = Array(m) { u -> DoubleArray(m) { v -> a[u][v] } }
                for (u in 0 until m) aa[u][u] += lambda * max(a[u][u], 1e-12 * dmax + 1e-30)
                val delta = Linalg.solve(aa, g)
                if (delta == null) { lambda *= 10; continue }
                trial = p.copyOf()
                for (u in 0 until m) trial[free[u]] += delta[u]
                clamp(trial, lower, upper)
                newChi = chi2(model, d, trial)
                if (newChi <= chi) { accepted = true; break }
                lambda *= 10
            }
            if (!accepted) {
                converged = true
                message = "converged (no further improvement)"
                break
            }
            val improvement = chi - newChi
            var stepRel = 0.0
            for (u in 0 until m) {
                val k = free[u]
                stepRel = max(stepRel, abs(trial[k] - p[k]) / (abs(p[k]) + 100 * model.params[k].step))
            }
            System.arraycopy(trial, 0, p, 0, p.size)
            chi = newChi
            lambda = max(lambda / 10, 1e-12)
            if (improvement < 1e-9 * max(chi, 1.0) && stepRel < 1e-7) {
                smallSteps++
                if (smallSteps >= 2) { converged = true; message = "converged"; break }
            } else smallSteps = 0
        }
        if (!converged && message.isEmpty()) message = if (cancel()) "cancelled" else "reached iteration limit"

        model.normalize(p, d)
        chi = chi2(model, d, p)
        val n = d.n
        val ndf = n - m
        val chi2r = if (ndf > 0) chi / ndf else Double.NaN
        val errors = DoubleArray(p.size)
        val cov = Array(p.size) { DoubleArray(p.size) }
        val scaled = scaleErrors && chi2r.isFinite()
        if (computeCovariance && m > 0) {
            val jac = jacobian(model, d, p, free, lower, upper)
            val a = Array(m) { DoubleArray(m) }
            for (i in 0 until n) { val row = jac[i]; for (u in 0 until m) for (v in 0 until m) a[u][v] += row[u] * row[v] }
            var inv = Linalg.invert(a)
            if (inv == null) {
                for (u in 0 until m) a[u][u] *= (1 + 1e-9)
                inv = Linalg.invert(a)
            }
            val f = if (scaled) chi2r else 1.0
            for (u in 0 until m) {
                for (v in 0 until m) cov[free[u]][free[v]] = (inv?.get(u)?.get(v) ?: Double.NaN) * f
            }
            for (u in 0 until m) {
                val vv = cov[free[u]][free[u]]
                errors[free[u]] = if (vv.isFinite() && vv >= 0) sqrt(vv) else Double.NaN
            }
            if (inv == null) message += "; covariance singular (parameters degenerate)"
        }
        return FitResult(
            model, p, errors, fixed.copyOf(), cov, chi, n, m, converged, iter, message, scaled,
            residualRms(model, d, p), lower.copyOf(), upper.copyOf()
        )
    }

    /** Convenience: initial guess, default fixes and bounds from the model. */
    fun fitDefault(model: FitModel, ctx: FitContext, scaleErrors: Boolean, cancel: () -> Boolean = { false }): FitResult {
        val p0 = model.initial(ctx)
        return fit(model, ctx.data, p0, model.defaultFixed(ctx), model.lower(ctx), model.upper(ctx), scaleErrors, cancel = cancel)
    }

    fun evalCurve(model: FitModel, p: DoubleArray, t0: Double, t1: Double, n: Int = 400, x: Double = 0.0): Pair<DoubleArray, DoubleArray> {
        val ts = DoubleArray(n) { t0 + (t1 - t0) * it / (n - 1).toDouble() }
        return Pair(ts, DoubleArray(n) { model.value(ts[it], x, p) })
    }
}
