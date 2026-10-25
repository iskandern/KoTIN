package dev.iskandern.kotin.dxf

import kotlin.math.abs

/** Ребро границы HATCH; координаты — в OCS хэтча (2D). Углы эллипса и дуги — в градусах, как в DXF. */
sealed class HatchEdge {
  /** Ломаная с выпуклостями ([bulges] — для сегмента после вершины); отрезок LINE — две вершины. [xy] — пары x, y. */
  class Segments(val xy: DoubleArray, val bulges: DoubleArray, val closed: Boolean) : HatchEdge()

  class Arc(val cx: Double, val cy: Double, val radius: Double, val startDeg: Double, val endDeg: Double, val ccw: Boolean) : HatchEdge()

  /** [mx], [my] — конец большой полуоси относительно центра, [ratio] — отношение осей. */
  class Ellipse(
    val cx: Double, val cy: Double, val mx: Double, val my: Double, val ratio: Double,
    val startDeg: Double, val endDeg: Double, val ccw: Boolean,
  ) : HatchEdge()

  /**
   * [control] и [fit] — пары x, y; [weights] — null для нерационального сплайна; [startTangent]/[endTangent] —
   * касательные (x, y) в начале и конце для построения по опорным точкам, null — не заданы.
   */
  class Spline(
    val degree: Int, val knots: DoubleArray, val control: DoubleArray, val weights: DoubleArray?, val fit: DoubleArray,
    val startTangent: DoubleArray? = null, val endTangent: DoubleArray? = null,
  ) : HatchEdge()
}

/** Один контур границы HATCH: рёбра по порядку обхода. */
class HatchLoop(val edges: List<HatchEdge>)

/**
 * Семейство параллельных линий паттерна (коды 53, 43/44, 45/46, 49) — уже повёрнутое и отмасштабированное, как в DXF.
 * Линия k проходит через `база + k × смещение`; [dashes] — штрих > 0, пробел < 0, точка = 0; пусто — сплошная.
 */
class HatchPatternLine(
  val angleDeg: Double,
  val baseX: Double, val baseY: Double,
  val offsetX: Double, val offsetY: Double,
  val dashes: DoubleArray,
)

/** Паттерн штриховки: имя (код 2), поворот (52) и масштаб (41) — для стандартных имён без линий в файле. */
class HatchPattern(val name: String, val angleDeg: Double, val scale: Double, val lines: List<HatchPatternLine>)

/** Градиентная заливка (коды 450–470): имя типа (LINEAR, CYLINDER, …), цвета 0xRRGGBB и угол в радианах. */
class HatchGradient(val name: String, val color1: Int, val color2: Int, val angle: Double)

/**
 * Триангуляция полигонов с отверстиями и островками: вложенность по чётности (контур на нечётной глубине —
 * отверстие, на чётной — заливка), отверстия сливаются с внешним контуром мостами, затем ear clipping.
 * Без зависимостей, работает в commonMain.
 */
internal object HatchTriangulator {

  /**
   * [loops] — замкнутые контуры, пары x, y без повтора первой точки. Результат — углы треугольников
   * (x1 y1 x2 y2 x3 y3 подряд), все против часовой стрелки.
   */
  fun triangulate(loops: List<DoubleArray>): DoubleArray {
    val rings = loops.map { clean(it) }.filter { it.size >= 6 && abs(signedArea(it)) > 0.0 }
    if (rings.isEmpty()) return DoubleArray(0)

    // для каждого контура — индексы тех, кто его содержит
    val boxes = rings.map { bounds(it) }
    val containers = rings.indices.map { i ->
      rings.indices.filter { j -> j != i && boxContains(boxes[j], boxes[i]) && contains(rings[j], rings[i][0], rings[i][1]) }
    }
    val out = DoubleList()
    for (i in rings.indices) {
      if (containers[i].size % 2 != 0) continue
      // отверстия этой заливки — контуры, у которых ближайший содержащий контур — i
      val holes = rings.indices.filter { j -> containers[j].size == containers[i].size + 1 && i in containers[j] }
      earClip(merge(orient(rings[i], ccw = true), holes.map { orient(rings[it], ccw = false) }), out)
    }
    return out.toArray()
  }

  /**
   * Оставляет контуры по стилю заливки (код 75): 0 — все (чётность считает триангуляция), 1 — глубина вложенности
   * 0 и 1, 2 — только глубина 0.
   */
  fun applyStyle(loops: List<DoubleArray>, style: Int): List<DoubleArray> {
    if (style <= 0 || loops.size < 2) return loops
    val maxDepth = if (style == 1) 1 else 0
    val boxes = loops.map { bounds(it) }
    return loops.filterIndexed { i, loop ->
      var depth = 0
      for (j in loops.indices) {
        if (j != i && boxContains(boxes[j], boxes[i]) && contains(loops[j], loop[0], loop[1])) depth++
      }
      depth <= maxDepth
    }
  }

  // убирает подряд идущие одинаковые точки и повтор первой в конце
  private fun clean(xy: DoubleArray): DoubleArray {
    val out = DoubleList()
    for (i in 0 until xy.size / 2) {
      val n = out.size
      if (n >= 2 && out[n - 2] == xy[i * 2] && out[n - 1] == xy[i * 2 + 1]) continue
      out.add(xy[i * 2]); out.add(xy[i * 2 + 1])
    }
    while (out.size >= 4 && out[0] == out[out.size - 2] && out[1] == out[out.size - 1]) out.removeLastPoint()
    return out.toArray()
  }

  private fun signedArea(xy: DoubleArray): Double {
    var sum = 0.0
    val n = xy.size / 2
    for (i in 0 until n) {
      val j = (i + 1) % n
      sum += xy[i * 2] * xy[j * 2 + 1] - xy[j * 2] * xy[i * 2 + 1]
    }
    return sum / 2
  }

  private fun orient(xy: DoubleArray, ccw: Boolean): DoubleArray {
    if ((signedArea(xy) > 0) == ccw) return xy
    val n = xy.size / 2
    return DoubleArray(xy.size) { k -> xy[(n - 1 - k / 2) * 2 + k % 2] }
  }

  private fun bounds(xy: DoubleArray): DoubleArray {
    val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
    for (i in 0 until xy.size / 2) {
      b[0] = minOf(b[0], xy[i * 2]); b[1] = minOf(b[1], xy[i * 2 + 1])
      b[2] = maxOf(b[2], xy[i * 2]); b[3] = maxOf(b[3], xy[i * 2 + 1])
    }
    return b
  }

  private fun boxContains(outer: DoubleArray, inner: DoubleArray) =
    outer[0] <= inner[0] && outer[1] <= inner[1] && outer[2] >= inner[2] && outer[3] >= inner[3]

  /** Точка внутри многоугольника (чётность пересечений луча вправо). */
  fun contains(xy: DoubleArray, px: Double, py: Double): Boolean {
    var inside = false
    val n = xy.size / 2
    var j = n - 1
    for (i in 0 until n) {
      val yi = xy[i * 2 + 1]
      val yj = xy[j * 2 + 1]
      if ((yi > py) != (yj > py)) {
        val x = xy[i * 2] + (py - yi) / (yj - yi) * (xy[j * 2] - xy[i * 2])
        if (px < x) inside = !inside
      }
      j = i
    }
    return inside
  }

  /**
   * Сливает отверстия (по часовой) с внешним контуром (против часовой) мостами. Отверстия берутся справа налево:
   * от самой правой вершины отверстия луч вправо находит ребро внешнего кольца, мост идёт к видимой вершине.
   */
  private fun merge(outer: DoubleArray, holes: List<DoubleArray>): DoubleArray {
    var ring = outer
    for (hole in holes.sortedByDescending { h -> (0 until h.size / 2).maxOf { h[it * 2] } }) {
      val n = hole.size / 2
      var m = 0
      for (i in 1 until n) if (hole[i * 2] > hole[m * 2] || (hole[i * 2] == hole[m * 2] && hole[i * 2 + 1] < hole[m * 2 + 1])) m = i
      val k = bridgeTarget(ring, hole[m * 2], hole[m * 2 + 1])
      val merged = DoubleList()
      for (i in 0..k) { merged.add(ring[i * 2]); merged.add(ring[i * 2 + 1]) }
      for (s in 0..n) {
        val i = (m + s) % n
        merged.add(hole[i * 2]); merged.add(hole[i * 2 + 1])
      }
      for (i in k until ring.size / 2) { merged.add(ring[i * 2]); merged.add(ring[i * 2 + 1]) }
      ring = merged.toArray()
    }
    return ring
  }

  /** Индекс вершины кольца, видимой из точки (mx, my) отверстия. */
  private fun bridgeTarget(ring: DoubleArray, mx: Double, my: Double): Int {
    val n = ring.size / 2
    var bestX = Double.MAX_VALUE
    var edge = -1
    for (i in 0 until n) {
      val j = (i + 1) % n
      val ay = ring[i * 2 + 1]
      val by = ring[j * 2 + 1]
      if (ay == by || my < minOf(ay, by) || my > maxOf(ay, by)) continue
      val x = ring[i * 2] + (my - ay) / (by - ay) * (ring[j * 2] - ring[i * 2])
      if (x >= mx && x < bestX) { bestX = x; edge = i }
    }
    if (edge < 0) return 0
    val j = (edge + 1) % n
    val p = if (ring[edge * 2] > ring[j * 2]) edge else j
    if (bestX == mx) return p
    // отражающие вершины в треугольнике (M, I, P) заслоняют P — берём ту, что под наименьшим углом к лучу
    val px = ring[p * 2]
    val py = ring[p * 2 + 1]
    var best = p
    var bestTan = abs(py - my) / (px - mx)
    for (i in 0 until n) {
      if (i == p) continue
      val x = ring[i * 2]
      val y = ring[i * 2 + 1]
      if (x < mx || !inTriangle(x, y, mx, my, bestX, my, px, py) || !isReflex(ring, i)) continue
      val tan = abs(y - my) / (x - mx)
      if (tan < bestTan || (tan == bestTan && x < ring[best * 2])) { best = i; bestTan = tan }
    }
    return best
  }

  private fun isReflex(ring: DoubleArray, i: Int): Boolean {
    val n = ring.size / 2
    val p = (i + n - 1) % n
    val q = (i + 1) % n
    return cross(ring[p * 2], ring[p * 2 + 1], ring[i * 2], ring[i * 2 + 1], ring[q * 2], ring[q * 2 + 1]) < 0
  }

  private fun cross(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double) =
    (bx - ax) * (cy - by) - (by - ay) * (cx - bx)

  private fun inTriangle(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Boolean {
    val d1 = cross(ax, ay, bx, by, px, py)
    val d2 = cross(bx, by, cx, cy, px, py)
    val d3 = cross(cx, cy, ax, ay, px, py)
    return (d1 >= 0 && d2 >= 0 && d3 >= 0) || (d1 <= 0 && d2 <= 0 && d3 <= 0)
  }

  /** Ear clipping кольца против часовой стрелки (допускает повторные вершины от мостов). */
  private fun earClip(ring: DoubleArray, out: DoubleList) {
    val n = ring.size / 2
    val prev = IntArray(n) { (it + n - 1) % n }
    val next = IntArray(n) { (it + 1) % n }
    var remaining = n
    var v = 0
    var misses = 0
    fun x(i: Int) = ring[i * 2]
    fun y(i: Int) = ring[i * 2 + 1]

    fun emit(a: Int, b: Int, c: Int) {
      out.add(x(a)); out.add(y(a)); out.add(x(b)); out.add(y(b)); out.add(x(c)); out.add(y(c))
    }

    fun remove(i: Int) {
      next[prev[i]] = next[i]
      prev[next[i]] = prev[i]
      remaining--
      misses = 0
    }

    fun isEar(a: Int, b: Int, c: Int): Boolean {
      var p = next[c]
      while (p != a) {
        val sameCorner = (x(p) == x(a) && y(p) == y(a)) || (x(p) == x(b) && y(p) == y(b)) || (x(p) == x(c) && y(p) == y(c))
        if (!sameCorner && inTriangle(x(p), y(p), x(a), y(a), x(b), y(b), x(c), y(c))) return false
        p = next[p]
      }
      return true
    }

    while (remaining > 3) {
      val a = prev[v]
      val c = next[v]
      val turn = cross(x(a), y(a), x(v), y(v), x(c), y(c))
      when {
        turn == 0.0 -> { remove(v); v = c }
        turn > 0 && isEar(a, v, c) -> { emit(a, v, c); remove(v); v = c }
        else -> {
          v = c
          // ушей нет (самопересечение, вырожденность) — срезаем самую выпуклую вершину, чтобы закончить
          if (++misses > remaining) {
            var best = v
            var bestTurn = -Double.MAX_VALUE
            var p = v
            do {
              val t = cross(x(prev[p]), y(prev[p]), x(p), y(p), x(next[p]), y(next[p]))
              if (t > bestTurn) { bestTurn = t; best = p }
              p = next[p]
            } while (p != v)
            if (bestTurn > 0) emit(prev[best], best, next[best])
            v = next[best]
            remove(best)
          }
        }
      }
    }
    if (remaining == 3) {
      val a = prev[v]
      val c = next[v]
      if (cross(x(a), y(a), x(v), y(v), x(c), y(c)) > 0) emit(a, v, c)
    }
  }

  private class DoubleList {
    private var values = DoubleArray(32)
    var size = 0
      private set

    fun add(value: Double) {
      if (size == values.size) values = values.copyOf(size * 2)
      values[size++] = value
    }

    fun removeLastPoint() { size -= 2 }

    operator fun get(i: Int) = values[i]

    fun toArray(): DoubleArray = values.copyOf(size)
  }
}
