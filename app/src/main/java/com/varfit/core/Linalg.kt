package com.varfit.core

import kotlin.math.abs
import kotlin.math.sqrt

/** Small dense linear-algebra helpers (no external dependencies). */
object Linalg {

    /** Solves A x = b with partial pivoting. Returns null if A is singular. A and b are not modified. */
    fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }
        val x = b.copyOf()
        for (col in 0 until n) {
            var piv = col
            var best = abs(m[col][col])
            for (r in col + 1 until n) if (abs(m[r][col]) > best) { best = abs(m[r][col]); piv = r }
            if (best < 1e-300 || best.isNaN()) return null
            if (piv != col) {
                val tmp = m[piv]; m[piv] = m[col]; m[col] = tmp
                val tb = x[piv]; x[piv] = x[col]; x[col] = tb
            }
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                if (f == 0.0) continue
                for (c in col until n) m[r][c] -= f * m[col][c]
                x[r] -= f * x[col]
            }
        }
        for (r in n - 1 downTo 0) {
            var s = x[r]
            for (c in r + 1 until n) s -= m[r][c] * x[c]
            x[r] = s / m[r][r]
        }
        return if (x.all { it.isFinite() }) x else null
    }

    /** Inverse via Gauss-Jordan elimination. Returns null if singular. */
    fun invert(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        if (n == 0) return arrayOf()
        val m = Array(n) { r -> DoubleArray(2 * n) { c -> if (c < n) a[r][c] else if (c - n == r) 1.0 else 0.0 } }
        for (col in 0 until n) {
            var piv = col
            var best = abs(m[col][col])
            for (r in col + 1 until n) if (abs(m[r][col]) > best) { best = abs(m[r][col]); piv = r }
            if (best < 1e-300 || best.isNaN()) return null
            if (piv != col) { val t = m[piv]; m[piv] = m[col]; m[col] = t }
            val d = m[col][col]
            for (c in 0 until 2 * n) m[col][c] /= d
            for (r in 0 until n) {
                if (r == col) continue
                val f = m[r][col]
                if (f == 0.0) continue
                for (c in 0 until 2 * n) m[r][c] -= f * m[col][c]
            }
        }
        val inv = Array(n) { r -> DoubleArray(n) { c -> m[r][n + c] } }
        return if (inv.all { row -> row.all { it.isFinite() } }) inv else null
    }

    /** Lower Cholesky factor L with A = L Lᵀ, or null if A is not positive definite. */
    fun cholesky(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        val l = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in 0..i) {
                var s = a[i][j]
                for (k in 0 until j) s -= l[i][k] * l[j][k]
                if (i == j) {
                    if (s <= 0.0 || s.isNaN()) return null
                    l[i][i] = sqrt(s)
                } else {
                    l[i][j] = s / l[j][j]
                }
            }
        }
        return l
    }

    /** Weighted linear least squares: minimise Σ w (y - Σ_k c_k f_k(x))². Returns coefficients or null. */
    fun linearLeastSquares(basis: Array<DoubleArray>, y: DoubleArray, w: DoubleArray): DoubleArray? {
        val k = basis.size
        val n = y.size
        val ata = Array(k) { DoubleArray(k) }
        val aty = DoubleArray(k)
        for (i in 0 until n) {
            for (a in 0 until k) {
                val fa = basis[a][i] * w[i]
                aty[a] += fa * y[i]
                for (b in 0..a) ata[a][b] += fa * basis[b][i]
            }
        }
        for (a in 0 until k) for (b in a + 1 until k) ata[a][b] = ata[b][a]
        // tiny ridge for numerical safety
        for (a in 0 until k) ata[a][a] *= (1.0 + 1e-12)
        return solve(ata, aty)
    }
}
