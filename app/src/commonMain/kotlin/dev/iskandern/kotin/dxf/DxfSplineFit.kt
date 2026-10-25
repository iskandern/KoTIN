package dev.iskandern.kotin.dxf

import kotlin.math.sqrt

/** Кубический B-сплайн, построенный по опорным точкам: контрольные точки (x, y, z) и узлы; степень 3. */
class FitSpline(val control: DoubleArray, val knots: DoubleArray)

/**
 * Интерполяционный кубический сплайн (C2) через опорные точки [fit] (x, y, z): параметр — длина хорды, касательные
 * в начале и конце — [startTangent]/[endTangent] (направление, длина не важна), без них — естественные условия
 * (нулевая вторая производная). Замкнутый ([closed], от трёх точек) — периодический. Каждый пролёт — кривая Безье,
 * поэтому внутренние узлы кратности 3 (кривая та же, что с простыми узлами). null — меньше двух различных точек.
 */
fun interpolateFitPoints(
  fit: DoubleArray, startTangent: DoubleArray?, endTangent: DoubleArray?, closed: Boolean,
): FitSpline? {
  // точки без повторов подряд; у замкнутого — без повтора первой в конце
  val q = DoubleArray(fit.size)
  var count = 0
  for (i in 0 until fit.size / 3) {
    if (count > 0 && samePoint(fit, i, q, count - 1)) continue
    for (k in 0..2) q[count * 3 + k] = fit[i * 3 + k]
    count++
  }
  if (closed && count > 2 && samePoint(q, count - 1, q, 0)) count--
  if (count < 2) return null
  val periodic = closed && count >= 3
  val spans = if (periodic) count else count - 1
  fun next(k: Int) = if (k + 1 == count) 0 else k + 1
  val h = DoubleArray(spans) { k ->
    val j = next(k)
    val dx = q[j * 3] - q[k * 3]
    val dy = q[j * 3 + 1] - q[k * 3 + 1]
    val dz = q[j * 3 + 2] - q[k * 3 + 2]
    sqrt(dx * dx + dy * dy + dz * dz)
  }
  // наклон хорды пролёта k по координате c
  fun slope(k: Int, c: Int) = (q[next(k) * 3 + c] - q[k * 3 + c]) / h[k]

  // производные m в опорных точках: h_k·m_{k−1} + 2(h_{k−1} + h_k)·m_k + h_{k−1}·m_{k+1} = 3(h_k·δ_{k−1} + h_{k−1}·δ_k)
  val a = DoubleArray(count)
  val b = DoubleArray(count)
  val c = DoubleArray(count)
  val r = DoubleArray(count * 3)
  for (k in 0 until count) {
    val inner = periodic || (k > 0 && k < count - 1)
    if (inner) {
      val prev = if (k == 0) count - 1 else k - 1
      a[k] = h[k]
      b[k] = 2 * (h[prev] + h[k])
      c[k] = h[prev]
      for (d in 0..2) r[k * 3 + d] = 3 * (h[k] * slope(prev, d) + h[prev] * slope(k, d))
      continue
    }
    val first = k == 0
    val tangent = unit(if (first) startTangent else endTangent)
    if (tangent != null) {
      b[k] = 1.0
      for (d in 0..2) r[k * 3 + d] = tangent[d]
    } else {
      // естественный конец: 2·m_0 + m_1 = 3·δ_0, m_{n−1} + 2·m_n = 3·δ_{n−1}
      b[k] = 2.0
      if (first) c[k] = 1.0 else a[k] = 1.0
      for (d in 0..2) r[k * 3 + d] = 3 * slope(if (first) 0 else count - 2, d)
    }
  }
  val m = if (periodic) solveCyclic(a, b, c, r) else solveTridiagonal(a, b, c, r)

  // пролёт k → Безье: Q_k, Q_k + h·m_k/3, Q_{k+1} − h·m_{k+1}/3, Q_{k+1}
  val control = DoubleArray((spans * 3 + 1) * 3)
  val knots = DoubleArray(spans * 3 + 5)
  var t = 0.0
  for (i in 0..3) knots[i] = 0.0
  for (k in 0 until spans) {
    val j = next(k)
    for (d in 0..2) {
      control[(k * 3) * 3 + d] = q[k * 3 + d]
      control[(k * 3 + 1) * 3 + d] = q[k * 3 + d] + h[k] * m[k * 3 + d] / 3
      control[(k * 3 + 2) * 3 + d] = q[j * 3 + d] - h[k] * m[j * 3 + d] / 3
      control[(k * 3 + 3) * 3 + d] = q[j * 3 + d]
    }
    t += h[k]
    val last = if (k == spans - 1) 4 else 3
    for (i in 0 until last) knots[4 + k * 3 + i] = t
  }
  return FitSpline(control, knots)
}

private fun samePoint(p: DoubleArray, i: Int, s: DoubleArray, j: Int) =
  p[i * 3] == s[j * 3] && p[i * 3 + 1] == s[j * 3 + 1] && p[i * 3 + 2] == s[j * 3 + 2]

private fun unit(v: DoubleArray?): DoubleArray? {
  if (v == null) return null
  val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
  if (len == 0.0 || len.isNaN()) return null
  return doubleArrayOf(v[0] / len, v[1] / len, v[2] / len)
}

// метод прогонки; a — под диагональю, c — над ней, правая часть — три столбца (x, y, z)
private fun solveTridiagonal(a: DoubleArray, b: DoubleArray, c: DoubleArray, r: DoubleArray): DoubleArray {
  val n = b.size
  val cp = DoubleArray(n)
  val x = DoubleArray(n * 3)
  var denom = b[0]
  for (d in 0..2) x[d] = r[d] / denom
  for (i in 1 until n) {
    cp[i - 1] = c[i - 1] / denom
    denom = b[i] - a[i] * cp[i - 1]
    for (d in 0..2) x[i * 3 + d] = (r[i * 3 + d] - a[i] * x[(i - 1) * 3 + d]) / denom
  }
  for (i in n - 2 downTo 0) for (d in 0..2) x[i * 3 + d] -= cp[i] * x[(i + 1) * 3 + d]
  return x
}

// циклическая прогонка (Шерман — Моррисон): угловые элементы a[0] (строка 0) и c[n−1] (строка n−1)
private fun solveCyclic(a: DoubleArray, b: DoubleArray, c: DoubleArray, r: DoubleArray): DoubleArray {
  val n = b.size
  val beta = a[0]
  val alpha = c[n - 1]
  val gamma = -b[0]
  val bb = b.copyOf()
  bb[0] = b[0] - gamma
  bb[n - 1] = b[n - 1] - alpha * beta / gamma
  val x = solveTridiagonal(a, bb, c, r)
  val u = DoubleArray(n * 3)
  for (d in 0..2) {
    u[d] = gamma
    u[(n - 1) * 3 + d] = alpha
  }
  val z = solveTridiagonal(a, bb, c, u)
  for (d in 0..2) {
    val fact = (x[d] + beta * x[(n - 1) * 3 + d] / gamma) / (1 + z[d] + beta * z[(n - 1) * 3 + d] / gamma)
    for (i in 0 until n) x[i * 3 + d] -= fact * z[i * 3 + d]
  }
  return x
}
