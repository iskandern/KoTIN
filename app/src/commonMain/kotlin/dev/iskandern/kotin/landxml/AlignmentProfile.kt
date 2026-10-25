package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.ArcSteps
import dev.iskandern.kotin.model.GrowingDoubleArray
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Продольный профиль трассы `ProfAlign` [name]: «расстояние от начала трассы → отметка».
 * PVI ([pvis] пятёрками: пикет, отметка, вид [PVI]/[PARA]/[UNSYM]/[CIRC], два параметра кривой) переводятся
 * из пикетов в расстояния по [stationing] (с уравнениями `StaEquation`) и упорядочиваются. Между PVI — прямые по
 * уклону, у PVI — вертикальная кривая:
 * - `ParaCurve[length]` — симметричная парабола, `z = zНачала + g1·x + (g2 − g1)·x² / (2L)`;
 * - `UnsymParaCurve[lengthIn, lengthOut]` — две параболы, стык на пикете PVI с уклоном `(g1·l1 + g2·l2) / (l1 + l2)`;
 * - `CircCurve[length, radius]` — дуга окружности радиуса `radius`, касательная к обоим уклонам (без радиуса — `length / Δугла`).
 * У крайних PVI кривая не строится. Данные — массивами; [elevation] при растущих расстояниях идёт курсором, O(1) на точку.
 */
internal class AlignmentProfile(val name: String, stationing: Stationing, pvis: DoubleArray) {
  private val d: DoubleArray
  private val z: DoubleArray
  private val kind: IntArray
  // уклон участка k (от PVI k до k + 1)
  private val g: DoubleArray
  // кривая у PVI: начало, конец; парабола — коэффициенты; дуга — центр, радиус, знак (+1 — вогнутая)
  private val cs: DoubleArray
  private val ce: DoubleArray
  private val p0: DoubleArray
  private val p1: DoubleArray
  private val p2: DoubleArray
  private val p3: DoubleArray
  private var cursor = 0

  /** PVI с пикетом не на трассе (и не за её концами) или с повтором расстояния — пропущены. */
  val skipped: Int

  init {
    val count = pvis.size / PVI_SIZE
    val order = (0 until count).map { it to stationing.distanceBeyond(pvis[it * PVI_SIZE]) }
      .filter { !it.second.isNaN() && !pvis[it.first * PVI_SIZE + 1].isNaN() }
      .sortedBy { it.second }
    val kept = ArrayList<Pair<Int, Double>>(order.size)
    for (p in order) if (kept.isEmpty() || p.second - kept.last().second > EPS) kept.add(p)
    skipped = count - kept.size
    val n = kept.size
    d = DoubleArray(n) { kept[it].second }
    z = DoubleArray(n) { pvis[kept[it].first * PVI_SIZE + 1] }
    kind = IntArray(n) { pvis[kept[it].first * PVI_SIZE + 2].toInt() }
    g = DoubleArray(maxOf(n - 1, 0)) { (z[it + 1] - z[it]) / (d[it + 1] - d[it]) }
    cs = d.copyOf()
    ce = d.copyOf()
    p0 = DoubleArray(n)
    p1 = DoubleArray(n)
    p2 = DoubleArray(n)
    p3 = DoubleArray(n)
    for (i in 0 until n) {
      val at = kept[i].first * PVI_SIZE
      if (i == 0 || i == n - 1 || !curve(i, pvis[at + 3], pvis[at + 4])) kind[i] = PVI
    }
  }

  // параметры кривой у внутреннего PVI [i]; false — кривой нет (нулевая длина, нет данных)
  private fun curve(i: Int, a: Double, b: Double): Boolean {
    val g1 = g[i - 1]
    val g2 = g[i]
    when (kind[i]) {
      PARA, UNSYM -> {
        val l1 = if (kind[i] == PARA) a / 2 else a
        val l2 = if (kind[i] == PARA) a / 2 else b
        if (!(l1 >= 0.0 && l2 >= 0.0 && l1 + l2 > 0.0)) return false
        cs[i] = d[i] - l1
        ce[i] = d[i] + l2
        // уклон в стыке; у симметричной — средний
        val gc = (g1 * l1 + g2 * l2) / (l1 + l2)
        p0[i] = z[i] - g1 * l1
        p1[i] = gc
        p2[i] = l1
        p3[i] = l2
        kind[i] = UNSYM
        return true
      }
      CIRC -> {
        val a1 = atan(g1)
        val a2 = atan(g2)
        val turn = abs(a2 - a1)
        val r = if (b > 0.0) b else if (a > 0.0 && turn > 0.0) a / turn else Double.NaN
        if (!(r > 0.0) || turn == 0.0) return false
        val s = if (a2 > a1) 1.0 else -1.0
        val t = r * tan(turn / 2)
        cs[i] = d[i] - t * cos(a1)
        ce[i] = d[i] + t * cos(a2)
        // центр — от начала дуги по нормали к первому уклону, в сторону вогнутости
        p0[i] = cs[i] - s * r * sin(a1)
        p1[i] = z[i] - t * sin(a1) + s * r * cos(a1)
        p2[i] = r
        p3[i] = s
        return true
      }
      else -> return false
    }
  }

  /** Число PVI (после пропусков). */
  val size: Int get() = d.size

  /** Расстояние от начала трассы до первого PVI; NaN — профиля нет (меньше двух PVI). */
  val start: Double get() = if (d.size >= 2) d[0] else Double.NaN

  /** Расстояние до последнего PVI. */
  val end: Double get() = if (d.size >= 2) d[d.size - 1] else Double.NaN

  /** Отметка на расстоянии [distance] от начала трассы; NaN — за концами профиля. */
  fun elevation(distance: Double): Double {
    val x = locate(distance)
    if (x.isNaN()) return x
    val k = cursor
    return when {
      kind[k] != PVI && x < ce[k] -> curveZ(k, x)
      kind[k + 1] != PVI && x > cs[k + 1] -> curveZ(k + 1, x)
      else -> z[k] + g[k] * (x - d[k])
    }
  }

  /** Уклон (dz/dx) на расстоянии [distance]; NaN — за концами профиля. */
  fun grade(distance: Double): Double {
    val x = locate(distance)
    if (x.isNaN()) return x
    val k = cursor
    return when {
      kind[k] != PVI && x < ce[k] -> curveGrade(k, x)
      kind[k + 1] != PVI && x > cs[k + 1] -> curveGrade(k + 1, x)
      else -> g[k]
    }
  }

  /**
   * Отметка на пикете [station] трассы со [stationing]; NaN — пикета нет на трассе (и за её концами) или он за концами профиля.
   * Пикет за концом трассы — как у PVI ([Stationing.distanceBeyond]): длина по геометрии и пикет конца в файле расходятся в округлении.
   */
  fun elevationAt(stationing: Stationing, station: Double): Double {
    val distance = stationing.distanceBeyond(station)
    return if (distance.isNaN()) distance else elevation(distance)
  }

  // курсор на участок, где лежит [distance]; возвращает расстояние, прижатое к концам в пределах EPS, или NaN
  private fun locate(distance: Double): Double {
    val n = d.size
    if (n < 2 || distance.isNaN() || distance < d[0] - EPS || distance > d[n - 1] + EPS) return Double.NaN
    val x = distance.coerceIn(d[0], d[n - 1])
    var k = cursor
    while (k > 0 && x < d[k]) k--
    while (k < n - 2 && x > d[k + 1]) k++
    cursor = k
    return x
  }

  private fun curveZ(i: Int, x: Double): Double {
    if (kind[i] == CIRC) {
      val u = x - p0[i]
      return p1[i] - p3[i] * sqrt((p2[i] * p2[i] - u * u).coerceAtLeast(0.0))
    }
    val g1 = g[i - 1]
    val gc = p1[i]
    val l1 = p2[i]
    if (x < d[i] && l1 > 0.0) {
      val t = x - cs[i]
      return p0[i] + g1 * t + (gc - g1) * t * t / (2 * l1)
    }
    val t = x - d[i]
    return p0[i] + (g1 + gc) * l1 / 2 + gc * t + (g[i] - gc) * t * t / (2 * p3[i])
  }

  private fun curveGrade(i: Int, x: Double): Double {
    if (kind[i] == CIRC) {
      val u = x - p0[i]
      return p3[i] * u / sqrt((p2[i] * p2[i] - u * u).coerceAtLeast(0.0))
    }
    val g1 = g[i - 1]
    val gc = p1[i]
    return if (x < d[i] && p2[i] > 0.0) g1 +(gc - g1) * (x - cs[i]) / p2[i] else gc + (g[i] - gc) * (x - d[i]) / p3[i]
  }

  /**
   * Точки излома и разбиения профиля по расстоянию, по возрастанию: PVI, начала и концы кривых, внутри кривой — шаг,
   * при котором хорда отходит от кривой не больше [sceneSize]/[ArcSteps.SCENE_TOLERANCE_DIVISOR] (`h = √(8·допуск / z″)`).
   */
  fun breaks(sceneSize: Double): DoubleArray {
    val out = GrowingDoubleArray()
    val tolerance = sceneSize / ArcSteps.SCENE_TOLERANCE_DIVISOR
    for (i in d.indices) {
      if (kind[i] == PVI) {
        out.add(d[i])
        continue
      }
      val curvature = if (kind[i] == CIRC) 1 / p2[i] else maxOf(parabola(p1[i] - g[i - 1], p2[i]), parabola(g[i] - p1[i], p3[i]))
      val span = ce[i] - cs[i]
      val steps = if (tolerance > 0.0 && curvature > 0.0) {
        ceil(span / sqrt(8 * tolerance / curvature)).toInt().coerceIn(MIN_CURVE_STEPS, MAX_CURVE_STEPS)
      } else {
        MAX_CURVE_STEPS / 16
      }
      var pviAdded = false
      for (k in 0..steps) {
        val x = cs[i] + span * k / steps
        if (!pviAdded && x >= d[i]) {
          if (x - d[i] > EPS) out.add(d[i])
          pviAdded = true
        }
        out.add(x)
      }
    }
    val result = out.toArray()
    result.sort()
    return result
  }

  // z″ параболы с перепадом уклона [dg] на длине [l]
  private fun parabola(dg: Double, l: Double) = if (l > 0.0) abs(dg) / l else 0.0

  companion object {
    const val PVI = 0
    const val PARA = 1
    const val UNSYM = 2
    const val CIRC = 3

    /** Чисел на PVI в [pvis]: пикет, отметка, вид, параметры (`length`; `lengthIn`, `lengthOut`; `length`, `radius`). */
    const val PVI_SIZE = 5

    // расстояния ближе — одна точка
    const val EPS = 1e-6
    const val MIN_CURVE_STEPS = 4
    const val MAX_CURVE_STEPS = 1024

    /**
     * Профиль земли `ProfSurf` [name]: точки [points] парами «пикет, отметка» — ломаная без кривых. Точки прорежены:
     * отброшенная отходит по отметке от прямой между оставленными соседями не больше
     * [sceneSize]/[ArcSteps.SCENE_TOLERANCE_DIVISOR] (Дуглас — Пекер по расстоянию вдоль трассы). Null — меньше двух точек.
     */
    fun ground(name: String, stationing: Stationing, points: DoubleArray, sceneSize: Double): AlignmentProfile? {
      val order = (0 until points.size / 2).map { it to stationing.distanceBeyond(points[it * 2]) }
        .filter { !it.second.isNaN() && !points[it.first * 2 + 1].isNaN() }
        .sortedBy { it.second }
      if (order.size < 2) return null
      val n = order.size
      val d = DoubleArray(n) { order[it].second }
      val z = DoubleArray(n) { points[order[it].first * 2 + 1] }
      val keep = BooleanArray(n)
      keep[0] = true
      keep[n - 1] = true
      val tolerance = sceneSize / ArcSteps.SCENE_TOLERANCE_DIVISOR
      // участки «от — до» на проверку; стек вместо рекурсии — точек бывают тысячи
      val stack = ArrayList<Int>()
      stack.add(0)
      stack.add(n - 1)
      while (stack.isNotEmpty()) {
        val b = stack.removeAt(stack.size - 1)
        val a = stack.removeAt(stack.size - 1)
        if (b - a < 2) continue
        val span = d[b] - d[a]
        var worst = -1
        var deviation = tolerance
        for (i in a + 1 until b) {
          val line = if (span > EPS) z[a] + (z[b] - z[a]) * (d[i] - d[a]) / span else z[a]
          val dz = abs(z[i] - line)
          if (dz > deviation) {
            deviation = dz
            worst = i
          }
        }
        if (worst < 0) continue
        keep[worst] = true
        stack.add(a)
        stack.add(worst)
        stack.add(worst)
        stack.add(b)
      }
      val pvis = GrowingDoubleArray()
      for (i in 0 until n) {
        if (!keep[i]) continue
        pvis.add(points[order[i].first * 2])
        pvis.add(z[i])
        pvis.add(PVI.toDouble())
        pvis.add(Double.NaN)
        pvis.add(Double.NaN)
      }
      return AlignmentProfile(name, stationing, pvis.toArray()).takeIf { it.size >= 2 }
    }
  }
}

/**
 * Трасса [alignment] с профилем [profile] в 3D: один проход по элементам плана (шаги [AlignmentGeometry.elementSteps])
 * вместе с изломами профиля ([AlignmentProfile.breaks]); отметка — курсором профиля. За концами профиля отрезков нет.
 * Возвращает число отрезков.
 */
internal fun drawProfiled(
  alignment: AlignmentGeometry, profile: AlignmentProfile, lines: LandXmlSceneBuilder.Lines, sceneSize: Double, objectId: Int,
): Int {
  val breaks = profile.breaks(sceneSize)
  val point = DoubleArray(3)
  var count = 0
  var j = 0
  var last = Double.NEGATIVE_INFINITY
  var px = Double.NaN
  var py = 0.0
  var pz = 0.0
  var elementStart = 0.0

  fun emit(k: Int, offset: Double) {
    val distance = elementStart + offset
    if (distance < 0.0 || distance - last <= AlignmentProfile.EPS) return
    last = distance
    val z = profile.elevation(distance)
    if (z.isNaN()) {
      px = Double.NaN
      return
    }
    alignment.elementPoint(k, offset, point)
    if (!px.isNaN()) {
      lines.add(px, py, pz, point[0], point[1], z, objectId)
      count++
    }
    px = point[0]
    py = point[1]
    pz = z
  }

  for (k in 0 until alignment.elementCount) {
    val length = alignment.elementLength(k)
    if (length <= 0.0) continue
    val steps = alignment.elementSteps(k, sceneSize)
    for (s in 0..steps) {
      val offset = length * s / steps
      // изломы профиля до этой точки плана
      while (j < breaks.size && breaks[j] < elementStart + offset) emit(k, breaks[j++] - elementStart)
      emit(k, offset)
    }
    elementStart += length
  }
  return count
}
