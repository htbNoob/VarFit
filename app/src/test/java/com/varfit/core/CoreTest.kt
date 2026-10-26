package com.varfit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

class CoreTest {

    private fun dyPeg(): Session {
        val text = javaClass.classLoader!!.getResource("dypeg.csv")!!.readText()
        val s = Session()
        s.load(CsvImport.parse(text, "dypeg.csv"))
        s.target.starClass = StarClass.DSCT
        s.target.period = 0.0729263596
        s.target.epoch = 2438276.86155
        s.target.epochSystem = TimeSystem.HJD_UTC
        s.timeSystem = TimeSystem.HJD_UTC
        return s
    }

    @Test
    fun seestarImport() {
        val s = dyPeg()
        val ds = s.dataset!!
        assertEquals("DY Pegasi", ds.name)
        assertEquals(92, ds.obs.size)
        assertEquals(347.2143, ds.ra, 1e-3)
        assertEquals(30.0, ds.exposureSec, 1e-9)
        // mid-exposure of the first frame
        assertEquals((2461339.245907 + 2461339.246258) / 2, ds.obs[0].jdUtc, 1e-9)
        assertEquals(85, s.fitData().n)
    }

    @Test
    fun timeConversionsMatchAstropy() {
        // reference values computed with astropy 7 (DE builtin ephemeris)
        val jd = 2461339.246082
        val hjd = TimeSystems.convert(jd, 347.214349, 17.21666, TimeSystem.HJD_UTC)
        val bjd = TimeSystems.convert(jd, 347.214349, 17.21666, TimeSystem.BJD_TDB)
        assertEquals(2461339.25038876, hjd, 1.0 / 86400)
        assertEquals(2461339.25118601, bjd, 1.0 / 86400)
    }

    @Test
    fun dyPegMaximum() {
        val s = dyPeg()
        val d = s.fitData()
        val ctx = FitContext(d, s.target, EventKind.MAX, s.epochInFitSystem()!! - d.tRef)
        val out = AutoFit.run(ModelRegistry.forClass(StarClass.DSCT, d.n), ctx, true, 11)
        assertTrue(out.failures.isEmpty())
        val f2 = out.results.first { it.model.id == "fourier2" }
        val ev = Analysis.eventTime(f2, d, EventKind.MAX)!!
        assertEquals(EventKind.MAX, ev.kind)
        assertEquals(2461339.2662, ev.tRel + d.tRef, 0.001)
        assertTrue(ev.err > 0 && ev.err < 0.002)
        // the null model must be ranked last
        assertEquals("line", out.results.last().model.id)
    }

    @Test
    fun transitFluxMatchesExactUniformDisk() {
        fun exact(z: Double, k: Double): Double {
            if (z >= 1 + k) return 1.0
            if (z <= 1 - k) return 1 - k * k
            val k0 = acos((k * k + z * z - 1) / (2 * k * z))
            val k1 = acos((1 - k * k + z * z) / (2 * z))
            val a = (k * k * k0 + k1 - 0.5 * sqrt(4 * z * z - (1 + z * z - k * k) * (1 + z * z - k * k))) / Math.PI
            return 1 - a
        }
        var z = 0.0
        while (z < 1.2) {
            assertEquals(exact(z, 0.12), TransitMath.flux(z, 0.12, 0.0, 0.0), 5e-5)
            z += 0.01
        }
    }

    @Test
    fun syntheticTransitIsRecovered() {
        val rnd = Random(3)
        val truth = 2461340.40000
        val model = LimbDarkenedTransitModel()
        val p = doubleArrayOf(11.0, 0.0, 0.0, 0.0, 0.15, 8.0, 0.3, 2.5, 0.45, 0.27)
        val sb = StringBuilder("Name,Type,RA,DEC,Valid Reference Stars,Mag(obs),Mag(err),EXP,EXP start(JD),EXP end(JD),SNR\n")
        var jd = 2461340.33
        val off = TimeSystems.offset(jd, 300.0, 22.0, TimeSystem.JD_UTC, TimeSystem.BJD_TDB)
        while (jd < 2461340.33 + 3.5 / 24) {
            val mag = model.value(jd + 15.0 / 86400 + off - truth, 0.0, p) + rnd.nextGaussian() * 0.02
            sb.append(String.format(Locale.US, "Star,Non-reference star,300.0,22.0,15,%.6f,0.025,30.0s,%.6f,%.6f,40\n", mag, jd, jd + 30.0 / 86400))
            jd += 31.0 / 86400
        }
        val s = Session()
        s.load(CsvImport.parse(sb.toString(), "syn.csv"))
        s.target.starClass = StarClass.TRANSIT
        s.timeSystem = TimeSystem.BJD_TDB
        s.target.period = 2.5
        s.target.epoch = truth - 250.0
        s.target.epochSystem = TimeSystem.BJD_TDB
        s.target.aRs = 8.0
        s.target.inclination = Math.toDegrees(acos(0.3 / 8))
        s.target.rpRs = 0.14
        val d = s.fitData()
        val ctx = FitContext(d, s.target, EventKind.TRANSIT, s.epochInFitSystem()!! - d.tRef)
        val m = LimbDarkenedTransitModel()
        val r = Fitter.fitDefault(m, ctx, true)
        val tc = r.params[3] + d.tRef
        assertTrue("Tc off by ${(tc - truth) * 1440} min", abs(tc - truth) < 4 * r.errors[3])
        assertEquals(0.15, r.params[4], 0.04)

        val ex = AavsoExport.exoplanet(s, AavsoSettings().apply { obsCode = "TEST" }, "test", r, d, r.residualRms)
        val header = ex.text.lines().filter { it.startsWith("#") }.map { it.substringBefore('=') }
        for (k in listOf("#TYPE", "#OBSCODE", "#SOFTWARE", "#DELIM", "#DATE_TYPE", "#OBSTYPE", "#STAR_NAME", "#EXOPLANET_NAME",
            "#BINNING", "#EXPOSURE_TIME", "#FILTER", "#DETREND_PARAMETERS", "#MEASUREMENT_TYPE")) {
            assertTrue("missing $k", k in header)
        }
        assertTrue(ex.text.contains("#DATE_TYPE=BJD_TDB"))
    }

    @Test
    fun aavsoExtendedFormat() {
        val s = dyPeg()
        val st = AavsoSettings().apply { obsCode = "abc"; starId = "DY Peg"; checkName = "000-BBX-123"; checkMag = "10.512" }
        val ex = AavsoExport.extended(s, st, "VarFit test", 0.033)
        val lines = ex.text.lines().filter { it.isNotBlank() }
        assertEquals("#TYPE=EXTENDED", lines[0])
        assertEquals("#OBSCODE=ABC", lines[1])
        val data = lines.filter { !it.startsWith("#") }
        assertEquals(85, data.size)
        for (l in data) {
            val f = l.split(',')
            assertEquals(15, f.size)
            assertTrue(f[2].contains('.'))      // magnitude must contain a decimal point
            assertTrue(f[3].length <= 6)
            assertEquals("ENSEMBLE", f[7])
            assertEquals("na", f[8])
        }
    }

    @Test
    fun eclipseMinimumAndKwee() {
        val rnd = Random(5)
        val mk = MikulasekModel()
        val pe = doubleArrayOf(10.0, 0.0, 0.0, 0.6, 0.02, 1.2)
        val n = 120
        val ts = DoubleArray(n) { -0.06 + it * 0.001 }
        val ys = DoubleArray(n) { mk.value(ts[it], 0.0, pe) + rnd.nextGaussian() * 0.015 }
        val fd = FitData(ts, ys, DoubleArray(n) { 0.015 }, DoubleArray(n), 2461000.0, TimeSystem.HJD_UTC, IntArray(n) { it }, false, false)
        val ctx = FitContext(fd, TargetInfo().apply { starClass = StarClass.ECL }, EventKind.MIN, null)
        val r = Fitter.fitDefault(MikulasekModel(), ctx, true)
        assertEquals(0.0, r.params[2], 4 * r.errors[2])
        val k = Analysis.kweeVanWoerden(ts, ys)!!
        assertEquals(0.0, k.tRel, 60.0 / 86400)
    }
}
