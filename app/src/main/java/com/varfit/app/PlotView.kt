package com.varfit.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.varfit.core.Chi2MapResult
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Light-curve / periodogram / χ²-map plot drawn with Canvas. */
class PlotView(context: Context) : View(context) {

    enum class Mode { EMPTY, LIGHT, PERIODOGRAM, MAP }

    class Pt(val t: Double, val m: Double, val e: Double, val used: Boolean, val index: Int)

    var mode = Mode.EMPTY
    var emptyText = "No data"

    // light curve (times absolute; plotted as hours from tRefPlot)
    var points: List<Pt> = emptyList()
    var binT: DoubleArray? = null
    var binM: DoubleArray? = null
    var curveT: DoubleArray? = null
    var curveM: DoubleArray? = null
    var residT: DoubleArray? = null
    var residM: DoubleArray? = null
    var tRefPlot = 0.0
    var timeLabel = ""
    var eventMarks: List<Pair<Double, String>> = emptyList()
    var window: Pair<Double, Double>? = null
    var onPointTap: ((Int) -> Unit)? = null

    // periodogram
    var pgF: DoubleArray? = null
    var pgP: DoubleArray? = null
    var pgMark: Double? = null

    // χ² map
    var map: Chi2MapResult? = null
    var mapXLabel = ""
    var mapYLabel = ""
    var mapXOffset = 0.0
    var mapYOffset = 0.0
    var mapXScale = 1.0
    var mapYScale = 1.0

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f * d }
    private var plotRect = RectF()
    private var xMin = 0.0; private var xMax = 1.0; private var yMin = 0.0; private var yMax = 1.0

    fun clear(msg: String = "No data") { mode = Mode.EMPTY; emptyText = msg; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Pal.surface)
        text.color = Pal.muted
        when (mode) {
            Mode.EMPTY -> {
                text.textAlign = Paint.Align.CENTER
                canvas.drawText(emptyText, width / 2f, height / 2f, text)
                text.textAlign = Paint.Align.LEFT
            }
            Mode.LIGHT -> drawLight(canvas)
            Mode.PERIODOGRAM -> drawPeriodogram(canvas)
            Mode.MAP -> drawMap(canvas)
        }
    }

    // ------------------------------------------------------------------ axes helpers

    private fun niceStep(range: Double, target: Int): Double {
        if (range <= 0 || !range.isFinite()) return 1.0
        val raw = range / target
        val mag = 10.0.pow(floor(log10(raw)))
        val f = raw / mag
        val nf = when { f < 1.5 -> 1.0; f < 3 -> 2.0; f < 7 -> 5.0; else -> 10.0 }
        return nf * mag
    }

    private fun fmt(v: Double, step: Double): String {
        val dec = max(0, -floor(log10(step)).toInt())
        return String.format(Locale.US, "%.${min(dec, 6)}f", v)
    }

    private fun sx(x: Double, r: RectF, a: Double, b: Double) = (r.left + (x - a) / (b - a) * r.width()).toFloat()
    private fun sy(y: Double, r: RectF, a: Double, b: Double, invert: Boolean) =
        if (invert) (r.top + (y - a) / (b - a) * r.height()).toFloat() else (r.bottom - (y - a) / (b - a) * r.height()).toFloat()

    private fun drawAxes(c: Canvas, r: RectF, x0: Double, x1: Double, y0: Double, y1: Double, invertY: Boolean,
                         xLabel: String?, yLabel: String?, showXTicks: Boolean = true) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        val xs = niceStep(x1 - x0, 5)
        val ys = niceStep(y1 - y0, 4)
        text.color = Pal.muted
        var x = ceil(x0 / xs) * xs
        while (x <= x1 + 1e-12) {
            val px = sx(x, r, x0, x1)
            paint.color = Pal.plotGrid
            c.drawLine(px, r.top, px, r.bottom, paint)
            if (showXTicks) {
                text.textAlign = Paint.Align.CENTER
                c.drawText(fmt(x, xs), px, r.bottom + 13 * d, text)
            }
            x += xs
        }
        var y = ceil(y0 / ys) * ys
        while (y <= y1 + 1e-12) {
            val py = sy(y, r, y0, y1, invertY)
            paint.color = Pal.plotGrid
            c.drawLine(r.left, py, r.right, py, paint)
            text.textAlign = Paint.Align.RIGHT
            c.drawText(fmt(y, ys), r.left - 4 * d, py + 4 * d, text)
            y += ys
        }
        paint.color = Pal.border
        c.drawRect(r, paint)
        text.textAlign = Paint.Align.LEFT
        if (xLabel != null) {
            text.textAlign = Paint.Align.CENTER
            c.drawText(xLabel, r.centerX(), r.bottom + 27 * d, text)
            text.textAlign = Paint.Align.LEFT
        }
        if (yLabel != null) {
            c.save()
            c.rotate(-90f, 11 * d, r.centerY())
            text.textAlign = Paint.Align.CENTER
            c.drawText(yLabel, 11 * d, r.centerY() + 4 * d, text)
            c.restore()
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun h(t: Double) = (t - tRefPlot) * 24.0

    // ------------------------------------------------------------------ light curve

    private fun drawLight(c: Canvas) {
        if (points.isEmpty()) { mode = Mode.EMPTY; drawLightEmpty(c); return }
        val hasRes = residT != null && residM != null && residT!!.isNotEmpty()
        val left = 54 * d; val right = width - 10 * d; val top = 10 * d
        val bottom = height - 34 * d
        val split = if (hasRes) top + (bottom - top) * 0.72f else bottom
        val main = RectF(left, top, right, split - (if (hasRes) 6 * d else 0f))
        plotRect = main

        val ts = points.map { h(it.t) }
        var x0 = ts.minOrNull()!!; var x1 = ts.maxOrNull()!!
        curveT?.let { ct -> if (ct.isNotEmpty()) { x0 = min(x0, h(ct.first())); x1 = max(x1, h(ct.last())) } }
        val pad = max((x1 - x0) * 0.03, 1e-3)
        x0 -= pad; x1 += pad
        val ms = points.filter { it.used }.map { it.m }.ifEmpty { points.map { it.m } }
        var y0 = ms.minOrNull()!!; var y1 = ms.maxOrNull()!!
        curveM?.let { cm -> cm.forEach { if (it.isFinite()) { y0 = min(y0, it); y1 = max(y1, it) } } }
        val ypad = max((y1 - y0) * 0.08, 0.005)
        y0 -= ypad; y1 += ypad
        xMin = x0; xMax = x1; yMin = y0; yMax = y1

        drawAxes(c, main, x0, x1, y0, y1, true, if (hasRes) null else timeLabel, "mag", showXTicks = !hasRes)

        // fit window shading
        window?.let { (a, b) ->
            paint.style = Paint.Style.FILL
            paint.color = Pal.plotWindow
            c.drawRect(max(sx(h(a), main, x0, x1), main.left), main.top, min(sx(h(b), main, x0, x1), main.right), main.bottom, paint)
        }
        // event markers
        for ((t, label) in eventMarks) {
            val px = sx(h(t), main, x0, x1)
            if (px < main.left || px > main.right) continue
            paint.style = Paint.Style.STROKE
            paint.color = Pal.plotEvent
            paint.strokeWidth = 1.5f * d
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
            c.drawLine(px, main.top, px, main.bottom, paint)
            paint.pathEffect = null
            text.color = Pal.plotEvent
            c.drawText(label, px + 3 * d, main.top + 12 * d, text)
        }
        // points
        c.save()
        c.clipRect(main)
        for (p in points) {
            val px = sx(h(p.t), main, x0, x1)
            val py = sy(p.m, main, y0, y1, true)
            if (p.used && p.e.isFinite()) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = colorWithAlpha(Pal.plotData, 90)
                c.drawLine(px, sy(p.m - p.e, main, y0, y1, true), px, sy(p.m + p.e, main, y0, y1, true), paint)
            }
            if (p.used) {
                paint.style = Paint.Style.FILL
                paint.color = Pal.plotData
                c.drawCircle(px, py, 2.6f * d, paint)
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f * d
                paint.color = Pal.plotExcluded
                c.drawCircle(px, py, 3f * d, paint)
            }
        }
        val bt = binT; val bm = binM
        if (bt != null && bm != null) {
            paint.style = Paint.Style.FILL
            paint.color = Pal.plotBinned
            for (i in bt.indices) {
                val px = sx(h(bt[i]), main, x0, x1); val py = sy(bm[i], main, y0, y1, true)
                val s = 3.6f * d
                val path = Path().apply { moveTo(px, py - s); lineTo(px + s, py); lineTo(px, py + s); lineTo(px - s, py); close() }
                c.drawPath(path, paint)
            }
        }
        val ct = curveT; val cm = curveM
        if (ct != null && cm != null && ct.size > 1) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f * d
            paint.color = Pal.plotModel
            val path = Path()
            var started = false
            for (i in ct.indices) {
                if (!cm[i].isFinite()) { started = false; continue }
                val px = sx(h(ct[i]), main, x0, x1); val py = sy(cm[i], main, y0, y1, true)
                if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            }
            c.drawPath(path, paint)
        }
        c.restore()

        if (hasRes) {
            val rr = RectF(left, split + 4 * d, right, bottom)
            val rt = residT!!; val rm = residM!!
            val amax = max(rm.maxOfOrNull { abs(it) } ?: 0.01, 0.005) * 1.15
            drawAxes(c, rr, x0, x1, -amax, amax, true, timeLabel, "O−C")
            paint.color = Pal.plotModel
            paint.strokeWidth = 1f
            val zy = sy(0.0, rr, -amax, amax, true)
            c.drawLine(rr.left, zy, rr.right, zy, paint)
            paint.style = Paint.Style.FILL
            paint.color = Pal.plotData
            c.save(); c.clipRect(rr)
            for (i in rt.indices) c.drawCircle(sx(h(rt[i]), rr, x0, x1), sy(rm[i], rr, -amax, amax, true), 2.2f * d, paint)
            c.restore()
        }
    }

    private fun drawLightEmpty(c: Canvas) {
        text.textAlign = Paint.Align.CENTER
        c.drawText(emptyText, width / 2f, height / 2f, text)
        text.textAlign = Paint.Align.LEFT
    }

    // ------------------------------------------------------------------ periodogram

    private fun drawPeriodogram(c: Canvas) {
        val f = pgF ?: return
        val p = pgP ?: return
        val r = RectF(54 * d, 10 * d, width - 10 * d, height - 34 * d)
        val x0 = f.first(); val x1 = f.last()
        val y1 = max(p.maxOrNull() ?: 1.0, 1e-6) * 1.08
        drawAxes(c, r, x0, x1, 0.0, y1, false, "frequency [cycles/day]", "power")
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * d
        paint.color = Pal.plotBinned
        val path = Path()
        for (i in f.indices) {
            val px = sx(f[i], r, x0, x1); val py = sy(p[i], r, 0.0, y1, false)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        c.drawPath(path, paint)
        pgMark?.let { fm ->
            val px = sx(fm, r, x0, x1)
            paint.color = Pal.plotModel
            c.drawLine(px, r.top, px, r.bottom, paint)
            text.color = Pal.plotModel
            c.drawText(String.format(Locale.US, "f = %.5f c/d  (P = %.6f d)", fm, 1 / fm), r.left + 6 * d, r.top + 14 * d, text)
        }
    }

    // ------------------------------------------------------------------ χ² map

    private fun viridis(t0: Double): Int {
        val t = t0.coerceIn(0.0, 1.0)
        val stops = arrayOf(
            intArrayOf(68, 1, 84), intArrayOf(65, 68, 135), intArrayOf(42, 120, 142),
            intArrayOf(34, 168, 132), intArrayOf(122, 209, 81), intArrayOf(253, 231, 37)
        )
        val x = t * (stops.size - 1)
        val i = min(floor(x).toInt(), stops.size - 2)
        val f = x - i
        val a = stops[i]; val b = stops[i + 1]
        return Color.rgb(
            (a[0] + f * (b[0] - a[0])).toInt(), (a[1] + f * (b[1] - a[1])).toInt(), (a[2] + f * (b[2] - a[2])).toInt()
        )
    }

    private fun drawMap(c: Canvas) {
        val m = map ?: return
        val r = RectF(58 * d, 10 * d, width - 46 * d, height - 36 * d)
        val xs = m.xs.map { (it + mapXOffset) * mapXScale }
        val ys = m.ys.map { (it + mapYOffset) * mapYScale }
        val nx = xs.size; val ny = ys.size
        val x0 = xs.first(); val x1 = xs.last(); val y0 = ys.first(); val y1 = ys.last()
        val scale = if (m.chi2rScale > 0 && m.chi2rScale.isFinite()) m.chi2rScale else 1.0
        val dmax = 30.0
        val cw = r.width() / nx; val chh = r.height() / ny
        paint.style = Paint.Style.FILL
        for (j in 0 until ny) for (i in 0 until nx) {
            val v = m.chi2[j][i]
            val dv = (v - m.minChi2) / scale
            paint.color = if (!dv.isFinite()) Pal.surface2 else viridis(1.0 - sqrt(min(dv, dmax) / dmax))
            val left = r.left + i * cw
            val top = r.bottom - (j + 1) * chh
            c.drawRect(left, top, left + cw + 1, top + chh + 1, paint)
        }
        // contours (marching squares on cell centres)
        paint.style = Paint.Style.STROKE
        val widths = floatArrayOf(2.2f, 1.6f, 1.1f)
        for ((li, lev) in Chi2MapResult.DELTA_LEVELS.withIndex()) {
            paint.color = Color.WHITE
            paint.strokeWidth = widths[li] * d
            val level = m.minChi2 + lev * scale
            for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
                val v = doubleArrayOf(m.chi2[j][i], m.chi2[j][i + 1], m.chi2[j + 1][i + 1], m.chi2[j + 1][i])
                if (v.any { !it.isFinite() }) continue
                val px = floatArrayOf(r.left + (i + 0.5f) * cw, r.left + (i + 1.5f) * cw, r.left + (i + 1.5f) * cw, r.left + (i + 0.5f) * cw)
                val py = floatArrayOf(r.bottom - (j + 0.5f) * chh, r.bottom - (j + 0.5f) * chh, r.bottom - (j + 1.5f) * chh, r.bottom - (j + 1.5f) * chh)
                val pts = ArrayList<Float>()
                for (e in 0 until 4) {
                    val a = e; val b = (e + 1) % 4
                    if ((v[a] - level) * (v[b] - level) < 0) {
                        val f = ((level - v[a]) / (v[b] - v[a])).toFloat()
                        pts.add(px[a] + f * (px[b] - px[a])); pts.add(py[a] + f * (py[b] - py[a]))
                    }
                }
                if (pts.size >= 4) c.drawLine(pts[0], pts[1], pts[2], pts[3], paint)
                if (pts.size >= 8) c.drawLine(pts[4], pts[5], pts[6], pts[7], paint)
            }
        }
        // minimum marker
        val bx = r.left + (m.bestI + 0.5f) * cw
        val by = r.bottom - (m.bestJ + 0.5f) * chh
        paint.color = Pal.plotModel
        paint.strokeWidth = 2.5f * d
        val s = 7 * d
        c.drawLine(bx - s, by, bx + s, by, paint)
        c.drawLine(bx, by - s, bx, by + s, paint)
        // axes (no grid over the heatmap)
        val xs0 = x0 - (x1 - x0) / (2 * (nx - 1)); val xs1 = x1 + (x1 - x0) / (2 * (nx - 1))
        val ys0 = y0 - (y1 - y0) / (2 * (ny - 1)); val ys1 = y1 + (y1 - y0) / (2 * (ny - 1))
        text.color = Pal.muted
        val xstep = niceStep(xs1 - xs0, 4)
        var x = ceil(xs0 / xstep) * xstep
        text.textAlign = Paint.Align.CENTER
        while (x <= xs1) { c.drawText(fmt(x, xstep), sx(x, r, xs0, xs1), r.bottom + 13 * d, text); x += xstep }
        c.drawText(mapXLabel, r.centerX(), r.bottom + 28 * d, text)
        val ystep = niceStep(ys1 - ys0, 4)
        var y = ceil(ys0 / ystep) * ystep
        text.textAlign = Paint.Align.RIGHT
        while (y <= ys1) { c.drawText(fmt(y, ystep), r.left - 4 * d, sy(y, r, ys0, ys1, false) + 4 * d, text); y += ystep }
        c.save(); c.rotate(-90f, 11 * d, r.centerY())
        text.textAlign = Paint.Align.CENTER
        c.drawText(mapYLabel, 11 * d, r.centerY() + 4 * d, text)
        c.restore()
        // colour bar
        val cb = RectF(r.right + 10 * d, r.top, r.right + 20 * d, r.bottom)
        paint.style = Paint.Style.FILL
        val nseg = 40
        for (k in 0 until nseg) {
            val f = k / (nseg - 1.0)
            paint.color = viridis(1.0 - sqrt(f))
            val top = cb.top + f.toFloat() * cb.height()
            c.drawRect(cb.left, top, cb.right, top + cb.height() / nseg + 1, paint)
        }
        text.textAlign = Paint.Align.LEFT
        text.textSize = 9f * d
        c.drawText("Δχ²", cb.left - 2 * d, cb.bottom + 12 * d, text)
        c.drawText("0", cb.right + 2 * d, cb.top + 8 * d, text)
        c.drawText("30", cb.right + 2 * d, cb.bottom, text)
        text.textSize = 11f * d
        text.textAlign = Paint.Align.LEFT
    }

    // ------------------------------------------------------------------ interaction / export

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode != Mode.LIGHT || onPointTap == null || points.isEmpty()) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action == MotionEvent.ACTION_UP) {
            val r = plotRect
            var best = -1
            var bestD = (22 * d).toDouble()
            for (p in points) {
                val px = sx(h(p.t), r, xMin, xMax)
                val py = sy(p.m, r, yMin, yMax, true)
                val dd = sqrt(((px - event.x) * (px - event.x) + (py - event.y) * (py - event.y)).toDouble())
                if (dd < bestD) { bestD = dd; best = p.index }
            }
            if (best >= 0) { onPointTap?.invoke(best); performClick() }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    /** Renders the plot at its current on-screen size, scaled up for a sharper image file. */
    fun toBitmap(scale: Float = 2f): Bitmap {
        val w = max(width, 1); val hgt = max(height, 1)
        val bmp = Bitmap.createBitmap((w * scale).toInt(), (hgt * scale).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(scale, scale)
        draw(c)
        return bmp
    }
}
