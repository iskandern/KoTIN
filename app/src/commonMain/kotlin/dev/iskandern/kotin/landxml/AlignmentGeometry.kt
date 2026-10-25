package dev.iskandern.kotin.landxml

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Пикетаж трассы: «расстояние от начала ↔ пикет» по `staStart` и уравнениям `StaEquation`.
 * Без уравнений пикет = [staStart] + расстояние. Уравнение с `staInternal` (= `staStart` + расстояние) начинает
 * участок, где пикет идёт от `staAhead` вверх (`staIncrement="increasing"`, по умолчанию) или вниз.
 */
internal class Stationing(
  val staStart: Double,
  /** Длина трассы. */
  val length: Double,
  /** Уравнения тройками: `staInternal`, `staAhead`, знак (+1 — пикеты растут, −1 — убывают); по возрастанию `staInternal`. */
  equations: DoubleArray = DoubleArray(0),
) {
  // участки: начало по «внутреннему» пикету, пикет в начале, знак; участок 0 — от staStart без уравнения
  private val internal: DoubleArray
  private val ahead: DoubleArray
  private val sign: DoubleArray

  init {
    val n = equations.size / 3 + 1
    internal = DoubleArray(n)
    ahead = DoubleArray(n)
    sign = DoubleArray(n)
    internal[0] = staStart
    ahead[0] = staStart
    sign[0] = 1.0
    for (k in 1 until n) {
      internal[k] = equations[(k - 1) * 3]
      ahead[k] = equations[(k - 1) * 3 + 1]
      sign[k] = equations[(k - 1) * 3 + 2]
    }
  }

  /** Пикет точки на расстоянии [distance] от начала трассы. */
  fun station(distance: Double): Double {
    val sta = staStart + distance
    var k = internal.size - 1
    while (k > 0 && internal[k] > sta) k--
    return ahead[k] + sign[k] * (sta - internal[k])
  }

  /** Расстояние от начала трассы до пикета [station]; NaN — такого пикета на трассе нет. При повторе пикета — первый. */
  fun distance(station: Double): Double {
    val end = staStart + length
    for (k in internal.indices) {
      val from = internal[k]
      val to = if (k + 1 < internal.size) internal[k + 1] else end
      if (to < from) continue
      val offset = (station - ahead[k]) * sign[k]
      if (offset >= -EPS && offset <= to - from + EPS) return (from + offset.coerceIn(0.0, to - from)) - staStart
    }
    return Double.NaN
  }

  /**
   * Пикеты, кратные [step], по порядку участков: [action] получает расстояние от начала и пикет. Участок, где пикет
   * сменяется уравнением, полуоткрытый (кроме последнего), так что стык не даёт двух подписей.
   */
  fun forEachMultiple(step: Double, action: (distance: Double, station: Double) -> Unit) {
    if (!(step > 0.0)) return
    val end = staStart + length
    for (k in internal.indices) {
      val span = (if (k + 1 < internal.size) internal[k + 1] else end) - internal[k]
      if (span < 0.0) continue
      val lo = if (sign[k] > 0) ahead[k] else ahead[k] - span
      val hi = if (sign[k] > 0) ahead[k] + span else ahead[k]
      // бесконечный или неохватный диапазон (мусор в длине и пикетах): на больших n шаг `n += 1` не меняет n
      if (!((hi - lo) / step <= MAX_MULTIPLES && abs(lo) / step < MAX_MULTIPLES && abs(hi) / step < MAX_MULTIPLES)) continue
      var n = ceil((lo - EPS) / step)
      while (n * step <= hi + EPS) {
        val station = n * step
        val offset = (station - ahead[k]) * sign[k]
        if (offset < span - EPS || (k == internal.size - 1 && offset <= span + EPS)) {
          action(internal[k] + offset - staStart, station)
        }
        n += 1.0
      }
    }
  }

  /**
   * Как [distance], но пикет за концом трассы — продолжение первого или последнего участка (расстояние меньше 0 или
   * больше длины): так PVI профиля за концами трассы задают уклон на ней. NaN — пикета нет и за концами.
   */
  fun distanceBeyond(station: Double): Double {
    val d = distance(station)
    if (!d.isNaN()) return d
    val before = (station - ahead[0]) * sign[0]
    if (before < 0) return before
    val k = internal.size - 1
    val after = (station - ahead[k]) * sign[k]
    val end = staStart + length
    return if (after > end - internal[k]) internal[k] + after - staStart else Double.NaN
  }

  private companion object {
    const val EPS = 1e-9

    // кратных шагу пикетов на участке — не больше (с запасом над лимитами подписей и засечек)
    const val MAX_MULTIPLES = 1e7
  }
}

/** Точка трассы: план и направление (радианы от оси x — востока — против часовой). */
internal class AlignmentPoint(val x: Double, val y: Double, val direction: Double)

/**
 * Плановая геометрия одной трассы: элементы [CoordGeom] с [first] до [last] (не включая) по порядку.
 * Длины — по геометрии (атрибутам `length` не доверяем); на ней строятся профиль, пикетаж и поперечники.
 */
internal class AlignmentGeometry(
  private val geom: CoordGeom,
  private val first: Int,
  private val last: Int,
  staStart: Double,
  equations: DoubleArray = DoubleArray(0),
) {
  // расстояние от начала трассы до начала элемента; последний — длина трассы
  private val starts = DoubleArray(last - first + 1)
  private val out = DoubleArray(3)

  init {
    for (i in first until last) starts[i - first + 1] = starts[i - first] + geom.length(i)
  }

  /** Длина по геометрии. */
  val length: Double get() = starts[last - first]

  /** Число элементов. */
  val elementCount: Int get() = last - first

  val stationing = Stationing(staStart, length, equations)

  /** Длина элемента [k] (с 0) по геометрии. */
  fun elementLength(k: Int): Double = starts[k + 1] - starts[k]

  /** Вид элемента [k] — [CoordGeom.KIND_LINE] и т. п. */
  fun elementKind(k: Int): Int = geom.kind(first + k)

  /** Точка элемента [k] на расстоянии [offset] от его начала в [out]: x, y, направление; без объектов на точку. */
  fun elementPoint(k: Int, offset: Double, out: DoubleArray) = geom.pointAt(first + k, offset, elementLength(k), out)

  /**
   * Шагов разбиения элемента [k] в плане по допуску от [sceneSize]: дуга — как в [CoordGeom.draw], переходная —
   * как дуга наименьшего радиуса (поворот θ на длине L — радиус не меньше L/2θ), ломаная — частым шагом, прямая — 1.
   */
  fun elementSteps(k: Int, sceneSize: Double): Int {
    val i = first + k
    val length = elementLength(k)
    if (length <= 0.0) return 1
    return when (geom.kind(i)) {
      CoordGeom.KIND_ARC -> CoordGeom.arcSteps(geom.sweep(i), geom.radius(i), sceneSize)
      CoordGeom.KIND_SPIRAL -> {
        geom.pointAt(i, 0.0, length, out)
        val from = out[2]
        geom.pointAt(i, length, length, out)
        val turn = abs(CoordGeom.normalize(out[2] - from))
        if (turn > 0.0) CoordGeom.arcSteps(turn, length / (2 * turn), sceneSize) else 1
      }
      // вершин ломаной отсюда не видно — частый шаг, излом срезается не больше чем на полшага
      CoordGeom.KIND_POLYLINE, CoordGeom.KIND_POLYLINE_3D ->
        if (sceneSize > 0.0) ceil(length * POLYLINE_STEPS / sceneSize).toInt().coerceIn(1, POLYLINE_STEPS) else POLYLINE_STEPS
      else -> 1
    }
  }

  /** Точка на пикете [station]; null — пикета на трассе нет. */
  fun at(station: Double): AlignmentPoint? {
    val d = stationing.distance(station)
    return if (d.isNaN()) null else atDistance(d)
  }

  /** Точка на расстоянии [distance] от начала (обрезается до длины трассы); null — у трассы нет геометрии. */
  fun atDistance(distance: Double): AlignmentPoint? {
    if (length <= 0.0) return null
    val d = distance.coerceIn(0.0, length)
    // элемент: последний, который начинается не дальше d и имеет длину
    var lo = 0
    var hi = last - first - 1
    while (lo < hi) {
      val mid = (lo + hi + 1) ushr 1
      if (starts[mid] <= d) lo = mid else hi = mid - 1
    }
    while (lo > 0 && elementLength(lo) <= 0.0) lo--
    while (lo < last - first - 1 && elementLength(lo) <= 0.0) lo++
    geom.pointAt(first + lo, d - starts[lo], elementLength(lo), out)
    return AlignmentPoint(out[0], out[1], out[2])
  }

  /**
   * Точки через [step] метров от начала и конец трассы — тройками x, y, направление; для пикетажа и поперечников.
   */
  fun sample(step: Double): DoubleArray {
    if (length <= 0.0 || !(step > 0.0)) return DoubleArray(0)
    val inner = ceil(length / step).toInt()
    val result = DoubleArray((inner + 1) * 3)
    for (k in 0..inner) {
      val p = atDistance(if (k == inner) length else k * step)!!
      result[k * 3] = p.x
      result[k * 3 + 1] = p.y
      result[k * 3 + 2] = p.direction
    }
    return result
  }

  private companion object {
    // шагов ломаной на размер сцены
    const val POLYLINE_STEPS = 2000
  }
}
