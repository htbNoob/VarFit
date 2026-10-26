package com.varfit.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

private const val NEG = Double.NEGATIVE_INFINITY
private const val POS = Double.POSITIVE_INFINITY

class ParamSpec(
    val name: String,
    val unit: String,
    val isTime: Boolean = false,
    /** step for numerical derivatives */
    val step: Double = 1e-4,
    val lower: Double = NEG,
    val upper: Double = POS,
    val description: String = ""
)

/** Context handed to models for initial guesses. Times are relative to data.tRef. */
class FitContext(
    val data: FitData,
    val target: TargetInfo,
    val event: EventKind,
    /** catalog epoch converted to the fit system, relative to tRef (or null) */
    val epochRel: Double?
) {
    val period: Double? get() = target.period?.takeIf { it > 0 }
    val tCenter: Double get() = 0.5 * (data.tMin + data.tMax)

    /** Catalog-predicted event time (relative) nearest the data center, or null. */
    fun predictedEvent(): Double? {
        val p = period ?: return null
        val e = epochRel ?: return null
        return e + p * round((tCenter - e) / p)
    }

    companion object {
        /** Resolves AUTO to MAX or MIN from the data shape. */
        fun resolveEvent(kind: EventKind, d: FitData): EventKind {
            if (kind != EventKind.AUTO) return kind
            val s = Shapes.smooth(d.y)
            val med = s.sorted()[s.size / 2]
            val bright = med - s.minOrNull()!!
            val faint = s.maxOrNull()!! - med
            return if (bright >= faint) EventKind.MAX else EventKind.MIN
        }
    }
}

object Shapes {
    /** Running median of width 5. */
    fun smooth(y: DoubleArray): DoubleArray {
        val n = y.size
        return DoubleArray(n) { i ->
            val a = max(0, i - 2); val b = min(n - 1, i + 2)
            val w = (a..b).map { y[it] }.sorted()
            w[w.size / 2]
        }
    }

    fun median(v: DoubleArray): Double { val s = v.sorted(); return if (s.isEmpty()) Double.NaN else s[s.size / 2] }

    /** Relative time of the smoothed extreme (brightest for MAX, faintest otherwise). */
    fun extremeTime(d: FitData, event: EventKind): Double {
        val s = smooth(d.y)
        var best = 0
        for (i in s.indices) {
            if (event == EventKind.MAX) { if (s[i] < s[best]) best = i } else { if (s[i] > s[best]) best = i }
        }
        return d.t[best]
    }

    fun extremeValue(d: FitData, event: EventKind): Double {
        val s = smooth(d.y)
        return if (event == EventKind.MAX) s.minOrNull()!! else s.maxOrNull()!!
    }

    /** Median of the outer 25 % of points at each end (baseline estimate). */
    fun baseline(d: FitData): Double {
        val n = d.n
        val k = max(1, n / 4)
        val idx = d.t.indices.sortedBy { d.t[it] }
        val v = (idx.take(k) + idx.takeLast(k)).map { d.y[it] }.toDoubleArray()
        return median(v)
    }
}

abstract class FitModel(val id: String, val name: String, val formula: String) {
    abstract val params: List<ParamSpec>
    abstract fun appliesTo(c: StarClass): Boolean
    /** Model magnitude at relative time t; x = airmass − 1. */
    abstract fun value(t: Double, x: Double, p: DoubleArray): Double
    abstract fun initial(ctx: FitContext): DoubleArray
    open fun defaultFixed(ctx: FitContext): BooleanArray = BooleanArray(params.size)
    open fun lower(ctx: FitContext): DoubleArray = DoubleArray(params.size) { params[it].lower }
    open fun upper(ctx: FitContext): DoubleArray = DoubleArray(params.size) { params[it].upper }
    /** Default pair of parameters for the 2D χ² map (x, y). */
    abstract fun mapPair(fixed: BooleanArray): Pair<Int, Int>
    /** Index of a parameter that directly equals the event time, or null if it must be found numerically. */
    open val timingIndex: Int? = null
    /** Brings equivalent solutions to a canonical form (e.g. T0 nearest the data, phases wrapped). */
    open fun normalize(p: DoubleArray, d: FitData) {}
    /** Event type described by the timing parameter for these parameters. */
    open fun timingEvent(p: DoubleArray, requested: EventKind): EventKind = requested
    val nParams get() = params.size
    fun index(name: String) = params.indexOfFirst { it.name == name }
    open val isTransitModel = false
    open val isPeriodic = false
    override fun toString() = name
}

// ----------------------------------------------------------------------------------------------

class LineModel : FitModel("line", "Constant + linear trend (null model)", "m = m0 + s·t") {
    override val params = listOf(
        ParamSpec("m0", "mag", step = 1e-4),
        ParamSpec("slope", "mag/d", step = 1e-4)
    )
    override fun appliesTo(c: StarClass) = true
    override fun value(t: Double, x: Double, p: DoubleArray) = p[0] + p[1] * t
    override fun initial(ctx: FitContext): DoubleArray {
        val d = ctx.data
        val w = DoubleArray(d.n) { 1.0 / (d.sigma[it] * d.sigma[it]) }
        val c = Linalg.linearLeastSquares(arrayOf(DoubleArray(d.n) { 1.0 }, d.t), d.y, w) ?: doubleArrayOf(d.y.average(), 0.0)
        return c
    }
    override fun mapPair(fixed: BooleanArray) = Pair(0, 1)
}

class PolyExtremumModel(val deg: Int) : FitModel(
    "poly$deg", "Polynomial extremum (degree $deg)",
    "m = m0 + Σ cₖ·((t−T0)/H)ᵏ, k = 2…$deg — T0 is the extremum"
) {
    /** time scale H (days), set from the data at initialisation */
    var h = 1.0 / 24.0
    override val params: List<ParamSpec> = buildList {
        add(ParamSpec("m0", "mag", step = 1e-4, description = "magnitude at the extremum"))
        add(ParamSpec("T0", "", isTime = true, step = 1e-5, description = "time of extremum"))
        for (k in 2..deg) add(ParamSpec("c$k", "mag", step = 1e-4, description = "coefficient of ((t−T0)/H)^$k"))
    }
    override fun appliesTo(c: StarClass) = c != StarClass.TRANSIT
    override fun value(t: Double, x: Double, p: DoubleArray): Double {
        val u = (t - p[1]) / h
        var s = p[0]
        var uk = u * u
        for (k in 2..deg) { s += p[k] * uk; uk *= u }
        return s
    }
    override val timingIndex: Int = 1
    override fun timingEvent(p: DoubleArray, requested: EventKind) = if (p[2] > 0) EventKind.MAX else EventKind.MIN

    override fun initial(ctx: FitContext): DoubleArray {
        val d = ctx.data
        h = max(d.span / 2.0, 1e-4)
        val ev = if (ctx.event == EventKind.TRANSIT) EventKind.MIN else ctx.event
        val t0 = Shapes.extremeTime(d, ev)
        val basis = Array(deg) { j -> DoubleArray(d.n) { i -> if (j == 0) 1.0 else ((d.t[i] - t0) / h).pow(j + 1) } }
        val w = DoubleArray(d.n) { 1.0 / (d.sigma[it] * d.sigma[it]) }
        val c = Linalg.linearLeastSquares(basis, d.y, w)
        val p = DoubleArray(nParams)
        p[1] = t0
        if (c != null) { p[0] = c[0]; for (k in 2..deg) p[k] = c[k - 1] } else p[0] = Shapes.extremeValue(d, ev)
        return p
    }
    override fun lower(ctx: FitContext): DoubleArray {
        val l = super.lower(ctx); l[1] = ctx.data.tMin - 0.5 * ctx.data.span; return l
    }
    override fun upper(ctx: FitContext): DoubleArray {
        val u = super.upper(ctx); u[1] = ctx.data.tMax + 0.5 * ctx.data.span; return u
    }
    override fun mapPair(fixed: BooleanArray) = Pair(1, 2)
}

/**
 * Fourier series with explicit epoch: m = m0 − A1·cos(ω(t−T0)) − Σ_{k≥2} A_k·cos(kω(t−T0)+φ_k), ω = 2π/P.
 * For a pure sinusoid T0 is the time of maximum light.
 */
class FourierModel(val harmonics: Int) : FitModel(
    "fourier$harmonics",
    if (harmonics == 1) "Sine (Fourier, 1 harmonic)" else "Fourier series ($harmonics harmonics)",
    "m = m0 − A1·cos(ω(t−T0)) − Σ Aₖ·cos(kω(t−T0)+φₖ), ω = 2π/P"
) {
    override val params: List<ParamSpec> = buildList {
        add(ParamSpec("m0", "mag", step = 1e-4, description = "mean magnitude"))
        add(ParamSpec("T0", "", isTime = true, step = 1e-5, description = "epoch of the 1st harmonic maximum"))
        add(ParamSpec("P", "d", step = 1e-7, lower = 1e-4, description = "period"))
        add(ParamSpec("A1", "mag", step = 1e-4, lower = 0.0, description = "1st harmonic semi-amplitude"))
        for (k in 2..harmonics) {
            add(ParamSpec("A$k", "mag", step = 1e-4, lower = 0.0))
            add(ParamSpec("φ$k", "rad", step = 1e-4))
        }
    }
    override val isPeriodic = true
    override fun appliesTo(c: StarClass) = c.isPulsator || c == StarClass.ECL || c == StarClass.OTHER

    override fun value(t: Double, x: Double, p: DoubleArray): Double {
        val w = 2 * PI / p[2]
        val ph = w * (t - p[1])
        var s = p[0] - p[3] * cos(ph)
        for (k in 2..harmonics) {
            val j = 4 + 2 * (k - 2)
            s -= p[j] * cos(k * ph + p[j + 1])
        }
        return s
    }

    fun guessPeriod(ctx: FitContext): Double {
        ctx.period?.let { return it }
        val d = ctx.data
        if (d.span > 0) {
            val pg = Analysis.periodogram(d.t, d.y, d.sigma, 0.7 / d.span, min(100.0, 0.25 * d.n / d.span), 4000)
            if (pg != null) return 1.0 / pg.bestFrequency
        }
        return max(2 * d.span, 0.01)
    }

    override fun initial(ctx: FitContext): DoubleArray {
        val d = ctx.data
        val per = guessPeriod(ctx)
        val w = 2 * PI / per
        val basis = ArrayList<DoubleArray>()
        basis.add(DoubleArray(d.n) { 1.0 })
        for (k in 1..harmonics) {
            basis.add(DoubleArray(d.n) { cos(k * w * d.t[it]) })
            basis.add(DoubleArray(d.n) { sin(k * w * d.t[it]) })
        }
        val wt = DoubleArray(d.n) { 1.0 / (d.sigma[it] * d.sigma[it]) }
        val c = Linalg.linearLeastSquares(basis.toTypedArray(), d.y, wt)
        val p = DoubleArray(nParams)
        p[2] = per
        if (c == null) {
            p[0] = d.y.average(); p[1] = Shapes.extremeTime(d, EventKind.MAX)
            p[3] = (d.y.maxOrNull()!! - d.y.minOrNull()!!) / 2
            return p
        }
        p[0] = c[0]
        val a1 = c[1]; val b1 = c[2]
        p[3] = sqrt(a1 * a1 + b1 * b1)
        var t0 = atan2(-b1, -a1) / w
        t0 += per * round((ctx.tCenter - t0) / per)
        p[1] = t0
        for (k in 2..harmonics) {
            val ak = c[2 * k - 1]; val bk = c[2 * k]
            val j = 4 + 2 * (k - 2)
            p[j] = sqrt(ak * ak + bk * bk)
            val psi = atan2(-bk, -ak)
            p[j + 1] = wrap(k * w * t0 - psi)
        }
        return p
    }

    override fun defaultFixed(ctx: FitContext): BooleanArray {
        val f = BooleanArray(nParams)
        val per = ctx.period
        f[2] = per != null && ctx.data.span < 2.5 * per
        return f
    }

    override fun lower(ctx: FitContext): DoubleArray {
        val l = super.lower(ctx)
        val per = ctx.period ?: guessPeriod(ctx)
        l[2] = per * 0.5
        return l
    }

    override fun upper(ctx: FitContext): DoubleArray {
        val u = super.upper(ctx)
        val per = ctx.period ?: guessPeriod(ctx)
        u[2] = per * 2.0
        return u
    }

    override fun normalize(p: DoubleArray, d: FitData) {
        val per = p[2]
        val center = 0.5 * (d.tMin + d.tMax)
        p[1] += per * round((center - p[1]) / per)
        for (k in 2..harmonics) { val j = 4 + 2 * (k - 2); p[j + 1] = wrap(p[j + 1]) }
    }

    override fun mapPair(fixed: BooleanArray) = if (!fixed[2]) Pair(1, 2) else Pair(1, 3)

    companion object {
        fun wrap(a: Double): Double {
            var x = a % (2 * PI)
            if (x > PI) x -= 2 * PI
            if (x <= -PI) x += 2 * PI
            return x
        }
    }
}

class GaussianModel : FitModel(
    "gauss", "Gaussian peak / dip + trend",
    "m = m0 + s·t + A·exp(−(t−T0)²/(2σ²))   (A > 0: dimming, A < 0: brightening)"
) {
    override val params = listOf(
        ParamSpec("m0", "mag", step = 1e-4),
        ParamSpec("slope", "mag/d", step = 1e-4),
        ParamSpec("T0", "", isTime = true, step = 1e-5),
        ParamSpec("A", "mag", step = 1e-4),
        ParamSpec("σ", "d", step = 1e-6, lower = 1e-5)
    )
    override fun appliesTo(c: StarClass) = c != StarClass.LPV
    override fun value(t: Double, x: Double, p: DoubleArray): Double {
        val u = (t - p[2]) / p[4]
        return p[0] + p[1] * t + p[3] * exp(-0.5 * u * u)
    }
    override val timingIndex: Int = 2
    override fun timingEvent(p: DoubleArray, requested: EventKind) =
        if (requested == EventKind.TRANSIT) EventKind.TRANSIT else if (p[3] > 0) EventKind.MIN else EventKind.MAX
    override fun initial(ctx: FitContext): DoubleArray {
        val d = ctx.data
        val ev = if (ctx.event == EventKind.TRANSIT) EventKind.MIN else ctx.event
        val base = Shapes.baseline(d)
        val t0 = ctx.predictedEvent()?.takeIf { it > d.tMin && it < d.tMax } ?: Shapes.extremeTime(d, ev)
        return doubleArrayOf(base, 0.0, t0, Shapes.extremeValue(d, ev) - base, max(d.span / 6.0, 1e-4))
    }
    override fun lower(ctx: FitContext) = super.lower(ctx).also { it[2] = ctx.data.tMin - 0.5 * ctx.data.span }
    override fun upper(ctx: FitContext) = super.upper(ctx).also { it[2] = ctx.data.tMax + 0.5 * ctx.data.span; it[4] = 5 * ctx.data.span }
    override fun mapPair(fixed: BooleanArray) = Pair(2, 4)
}

/** Phenomenological eclipse profile (Mikulášek 2015, A&A 584, A8). */
class MikulasekModel : FitModel(
    "mikulasek", "Eclipse profile (Mikulášek)",
    "m = m0 + s·t + A·(1 − {1 − exp[1 − cosh((t−T0)/D)]}^Γ)"
) {
    override val params = listOf(
        ParamSpec("m0", "mag", step = 1e-4),
        ParamSpec("slope", "mag/d", step = 1e-4),
        ParamSpec("T0", "", isTime = true, step = 1e-5),
        ParamSpec("A", "mag", step = 1e-4, description = "depth (A > 0: dimming)"),
        ParamSpec("D", "d", step = 1e-6, lower = 1e-5, description = "width"),
        ParamSpec("Γ", "", step = 1e-4, lower = 0.2, upper = 5.0, description = "kurtosis (flat-bottom) parameter")
    )
    override fun appliesTo(c: StarClass) = c == StarClass.ECL || c == StarClass.OTHER || c == StarClass.TRANSIT
    override fun value(t: Double, x: Double, p: DoubleArray): Double {
        val u = (t - p[2]) / p[4]
        val ch = if (abs(u) > 30) 1e13 else cosh(u)
        val inner = 1.0 - exp(1.0 - ch)
        val shape = 1.0 - (if (inner <= 0) 0.0 else inner.pow(p[5]))
        return p[0] + p[1] * t + p[3] * shape
    }
    override val timingIndex: Int = 2
    override fun timingEvent(p: DoubleArray, requested: EventKind) =
        if (requested == EventKind.TRANSIT) EventKind.TRANSIT else if (p[3] > 0) EventKind.MIN else EventKind.MAX
    override fun initial(ctx: FitContext): DoubleArray {
        val d = ctx.data
        val ev = if (ctx.event == EventKind.MAX) EventKind.MAX else EventKind.MIN
        val base = Shapes.baseline(d)
        val t0 = ctx.predictedEvent()?.takeIf { it > d.tMin && it < d.tMax } ?: Shapes.extremeTime(d, ev)
        return doubleArrayOf(base, 0.0, t0, Shapes.extremeValue(d, ev) - base, max(d.span / 8.0, 1e-4), 1.0)
    }
    override fun lower(ctx: FitContext) = super.lower(ctx).also { it[2] = ctx.data.tMin - 0.5 * ctx.data.span }
    override fun upper(ctx: FitContext) = super.upper(ctx).also { it[2] = ctx.data.tMax + 0.5 * ctx.data.span; it[4] = 5 * ctx.data.span }
    override fun mapPair(fixed: BooleanArray) = Pair(2, 4)
}

/** Trapezoid transit in flux, converted to magnitudes, with linear trend and airmass term. */
class TrapezoidTransitModel : FitModel(
    "trapezoid", "Transit: trapezoid",
    "F = 1 − δ·trapezoid(t; T0, T14, T12);  m = m0 + s·t + kX·(X−1) − 2.5·log10 F"
) {
    override val params = listOf(
        ParamSpec("m0", "mag", step = 1e-4, description = "out-of-transit magnitude"),
        ParamSpec("slope", "mag/d", step = 1e-4),
        ParamSpec("kX", "mag/airmass", step = 1e-4, description = "airmass coefficient"),
        ParamSpec("T0", "", isTime = true, step = 1e-5, description = "mid-transit time"),
        ParamSpec("δ", "", step = 1e-5, lower = 0.0, upper = 0.5, description = "depth (fraction of flux)"),
        ParamSpec("T14", "d", step = 1e-5, lower = 1e-3, description = "total duration"),
        ParamSpec("T12", "d", step = 1e-5, lower = 1e-4, description = "ingress duration")
    )
    override val isTransitModel = true
    override fun appliesTo(c: StarClass) = c == StarClass.TRANSIT
    override val timingIndex: Int = 3
    override fun value(t: Double, x: Double, p: DoubleArray): Double {
        val half = 0.5 * p[5]
        val ing = min(p[6], half)
        val dt = abs(t - p[3])
        val f = when {
            dt >= half -> 1.0
            dt <= half - ing -> 1.0 - p[4]
            else -> 1.0 - p[4] * (half - dt) / ing
        }
        return p[0] + p[1] * t + p[2] * x - 2.5 * ln(max(f, 1e-6)) / ln(10.0)
    }
    override fun initial(ctx: FitContext): DoubleArray {
        val d = ctx.data
        val tg = ctx.target
        val base = Shapes.baseline(d)
        val t14 = tg.t14Hours?.let { it / 24.0 } ?: max(d.span / 3.0, 0.02)
        val depth = tg.depthPct?.let { it / 100.0 } ?: tg.rpRs?.let { it * it }
            ?: (1.0 - 10.0.pow(-0.4 * (Shapes.extremeValue(d, EventKind.MIN) - base))).coerceIn(0.001, 0.2)
        val k = sqrt(depth)
        val t0 = ctx.predictedEvent() ?: Shapes.extremeTime(d, EventKind.MIN)
        return doubleArrayOf(base, 0.0, 0.0, t0, depth, t14, t14 * k / (1 + k) + 1e-3)
    }
    override fun defaultFixed(ctx: FitContext) = BooleanArray(nParams).also { it[2] = !ctx.data.hasAirmass }
    override fun lower(ctx: FitContext) = super.lower(ctx).also { it[3] = ctx.data.tMin - ctx.data.span }
    override fun upper(ctx: FitContext) = super.upper(ctx).also { it[3] = ctx.data.tMax + ctx.data.span; it[5] = 3 * ctx.data.span + 0.5 }
    override fun mapPair(fixed: BooleanArray) = Pair(3, 4)
}

/** Limb-darkened transit (quadratic law, circular orbit), numerically integrated. */
class LimbDarkenedTransitModel : FitModel(
    "ldtransit", "Transit: limb-darkened (Mandel–Agol type)",
    "F = occultation(z(t; T0, P, a/R*, b), Rp/R*, u1, u2);  m = m0 + s·t + kX·(X−1) − 2.5·log10 F"
) {
    override val params = listOf(
        ParamSpec("m0", "mag", step = 1e-4, description = "out-of-transit magnitude"),
        ParamSpec("slope", "mag/d", step = 1e-4),
        ParamSpec("kX", "mag/airmass", step = 1e-4, description = "airmass coefficient"),
        ParamSpec("T0", "", isTime = true, step = 1e-5, description = "mid-transit time"),
        ParamSpec("Rp/R*", "", step = 1e-5, lower = 0.005, upper = 0.6),
        ParamSpec("a/R*", "", step = 1e-3, lower = 1.2, upper = 300.0),
        ParamSpec("b", "", step = 1e-4, lower = 0.0, upper = 1.5, description = "impact parameter"),
        ParamSpec("P", "d", step = 1e-7, lower = 0.05, description = "orbital period"),
        ParamSpec("u1", "", step = 1e-4, lower = -1.0, upper = 2.0, description = "linear limb darkening"),
        ParamSpec("u2", "", step = 1e-4, lower = -1.0, upper = 2.0, description = "quadratic limb darkening")
    )
    override val isTransitModel = true
    override fun appliesTo(c: StarClass) = c == StarClass.TRANSIT
    override val timingIndex: Int = 3
    override fun value(t: Double, x: Double, p: DoubleArray): Double {
        val z = TransitMath.separation(t, p[3], p[7], p[5], p[6])
        val f = TransitMath.flux(z, p[4], p[8], p[9])
        return p[0] + p[1] * t + p[2] * x - 2.5 * ln(max(f, 1e-6)) / ln(10.0)
    }
    override fun initial(ctx: FitContext): DoubleArray {
        val d = ctx.data
        val tg = ctx.target
        val base = Shapes.baseline(d)
        val per = tg.period ?: 3.0
        val k = tg.rpRs ?: tg.depthPct?.let { sqrt(it / 100.0) }
            ?: sqrt((1.0 - 10.0.pow(-0.4 * (Shapes.extremeValue(d, EventKind.MIN) - base))).coerceIn(0.001, 0.2))
        var b = tg.impact ?: run {
            val a = tg.aRs; val i = tg.inclination
            if (a != null && i != null) a * cos(i * PI / 180.0) else 0.3
        }
        b = b.coerceIn(0.0, 1.0 + k - 0.01)
        val aRs = tg.aRs ?: run {
            val t14 = tg.t14Hours?.let { it / 24.0 } ?: (d.span / 3.0)
            val chord = sqrt(max((1 + k) * (1 + k) - b * b, 0.01))
            (per / (PI * t14) * chord).coerceIn(2.0, 200.0)
        }
        val t0 = ctx.predictedEvent() ?: Shapes.extremeTime(d, EventKind.MIN)
        return doubleArrayOf(base, 0.0, 0.0, t0, k, aRs, b, per, tg.u1, tg.u2)
    }
    override fun defaultFixed(ctx: FitContext): BooleanArray {
        val f = BooleanArray(nParams)
        val tg = ctx.target
        f[2] = !ctx.data.hasAirmass
        f[5] = tg.aRs != null      // a/R* poorly constrained by small telescopes: fix to catalog if known
        f[6] = tg.aRs != null && (tg.impact != null || tg.inclination != null)
        f[7] = true
        f[8] = true
        f[9] = true
        return f
    }
    override fun lower(ctx: FitContext) = super.lower(ctx).also { it[3] = ctx.data.tMin - ctx.data.span }
    override fun upper(ctx: FitContext) = super.upper(ctx).also { it[3] = ctx.data.tMax + ctx.data.span }
    override fun mapPair(fixed: BooleanArray) = Pair(3, 4)

    /** Total transit duration T14 in days. */
    fun t14(p: DoubleArray): Double {
        val k = p[4]; val aRs = p[5]; val b = p[6]; val per = p[7]
        val cosi = b / aRs
        val sini = sqrt(max(1 - cosi * cosi, 1e-12))
        val arg = sqrt(max((1 + k) * (1 + k) - b * b, 0.0)) / (aRs * sini)
        return per / PI * kotlin.math.asin(min(arg, 1.0))
    }

    fun inclinationDeg(p: DoubleArray) = acos((p[6] / p[5]).coerceIn(-1.0, 1.0)) * 180.0 / PI
}

object TransitMath {
    /** Sky-projected star–planet separation in stellar radii for a circular orbit (large value when behind the star). */
    fun separation(t: Double, t0: Double, period: Double, aRs: Double, b: Double): Double {
        val phi = 2 * PI * (t - t0) / period
        val c = cos(phi)
        if (c <= 0) return 1e9
        val x = aRs * sin(phi)
        val y = b * c
        return sqrt(x * x + y * y)
    }

    /**
     * Fraction of flux remaining when a planet of radius k (stellar radii) is at separation z,
     * quadratic limb darkening I(μ) = 1 − u1(1−μ) − u2(1−μ)². Numerical integration over stellar annuli.
     */
    fun flux(z: Double, k: Double, u1: Double, u2: Double, steps: Int = 64): Double {
        if (k <= 0 || z >= 1 + k) return 1.0
        val rMin = max(0.0, z - k)
        val rMax = min(1.0, z + k)
        if (rMax <= rMin) return 1.0
        val norm = PI * (1 - u1 / 3 - u2 / 6)
        val dr = rMax - rMin
        var sum = 0.0
        for (i in 0 until steps) {
            val s = (i + 0.5) / steps
            val r = rMin + dr * (1 - cos(PI * s)) / 2
            val drds = dr * PI * sin(PI * s) / 2
            val theta = if (r + z <= k) {
                2 * PI
            } else {
                val c = (r * r + z * z - k * k) / (2 * r * z)
                when {
                    c >= 1 -> 0.0
                    c <= -1 -> 2 * PI
                    else -> 2 * acos(c)
                }
            }
            val mu = sqrt(max(0.0, 1 - r * r))
            val om = 1 - mu
            val intensity = 1 - u1 * om - u2 * om * om
            sum += intensity * theta * r * drds
        }
        sum /= steps
        return 1 - sum / norm
    }
}

object ModelRegistry {
    private val ids = listOf(
        "line", "poly2", "poly3", "poly4", "poly5",
        "fourier1", "fourier2", "fourier3", "fourier4", "fourier5", "fourier6",
        "gauss", "mikulasek", "trapezoid", "ldtransit"
    )

    fun create(id: String): FitModel = when {
        id == "line" -> LineModel()
        id.startsWith("poly") -> PolyExtremumModel(id.removePrefix("poly").toInt())
        id.startsWith("fourier") -> FourierModel(id.removePrefix("fourier").toInt())
        id == "gauss" -> GaussianModel()
        id == "mikulasek" -> MikulasekModel()
        id == "trapezoid" -> TrapezoidTransitModel()
        id == "ldtransit" -> LimbDarkenedTransitModel()
        else -> throw IllegalArgumentException("Unknown model $id")
    }

    fun all(): List<FitModel> = ids.map { create(it) }

    /** Models applicable to the class with enough data points to constrain them. */
    fun forClass(c: StarClass, nPoints: Int): List<FitModel> =
        all().filter { it.appliesTo(c) && nPoints >= it.nParams + 3 }
}
