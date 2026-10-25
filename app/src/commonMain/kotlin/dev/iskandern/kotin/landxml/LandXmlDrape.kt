package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.SceneMesh
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Поверхность для посадки 2D-геометрии: треугольники сетки [mesh], индекс — лениво, при первом поиске.
 *
 * Индекс: соседи треугольников (через рёбра) и равномерная сетка ячеек по габаритам в CSR — `cellStart` (смещения)
 * и `cellTriangles` (номера треугольников); ячейка — по среднему размеру треугольника, ячеек не больше 4 × T.
 * Поиск точки начинается с треугольника предыдущей ([hint]) и идёт по соседям; не дошли — ячейка сетки.
 * Высота — барицентрическая интерполяция (плоскость треугольника). Алгоритм не зависит от формата: DXF с 2D-чертежом
 * и рельефом из 3DFACE мог бы сажать так же (KoTIN-74).
 */
internal class DrapeSurface(val name: String, val mesh: SceneMesh) {
  private val coords = mesh.coords
  private val triangles = mesh.triangles
  val triangleCount = mesh.triangleCount

  /** Площадь в плане — для выбора из нескольких поверхностей; считается один раз. */
  val area: Double by lazy {
    var sum = 0.0
    for (t in 0 until triangleCount) sum += abs(area2(t))
    sum / 2
  }

  /** Треугольник последней найденной точки: соседние точки линии ищутся от него. */
  var hint = -1

  private var built = false
  private lateinit var neighbors: IntArray
  private var edges = 0
  private var originX = 0.0
  private var originY = 0.0
  private var cell = 1.0
  private var nx = 0
  private var ny = 0
  private lateinit var cellStart: IntArray
  private lateinit var cellTriangles: IntArray

  // результат clip: параметры входа и выхода отрезка, ребро выхода (−1 — не выходит)
  var clipLo = 0.0
    private set
  var clipHi = 0.0
    private set
  var clipExit = -1
    private set

  /** Число рёбер (уникальных): предел дробления одной линии. */
  val edgeCount: Int get() = edges

  fun ensureIndex() {
    if (built) return
    built = true
    buildNeighbors()
    buildGrid()
  }

  // соседи: CSR «вершина → треугольники», затем у каждого ребра a–b — другой треугольник с a и b
  private fun buildNeighbors() {
    val vertexCount = mesh.vertexCount
    val start = IntArray(vertexCount + 1)
    for (k in 0 until triangleCount * 3) start[triangles[k] + 1]++
    for (v in 0 until vertexCount) start[v + 1] += start[v]
    val fill = start.copyOf(vertexCount)
    val incident = IntArray(triangleCount * 3)
    for (k in 0 until triangleCount * 3) incident[fill[triangles[k]]++] = k / 3
    val result = IntArray(triangleCount * 3)
    var boundary = 0
    for (t in 0 until triangleCount) {
      for (k in 0..2) {
        val a = triangles[t * 3 + k]
        val b = triangles[t * 3 + (k + 1) % 3]
        var found = -1
        for (j in start[a] until start[a + 1]) {
          val u = incident[j]
          if (u == t) continue
          if (triangles[u * 3] == b || triangles[u * 3 + 1] == b || triangles[u * 3 + 2] == b) {
            found = u
            break
          }
        }
        result[t * 3 + k] = found
        if (found < 0) boundary++
      }
    }
    neighbors = result
    edges = (triangleCount * 3 + boundary) / 2
  }

  // сетка: два прохода по треугольникам — подсчёт по ячейкам, затем заполнение
  private fun buildGrid() {
    originX = mesh.minX
    originY = mesh.minY
    val width = mesh.maxX - mesh.minX
    val height = mesh.maxY - mesh.minY
    val mean = if (triangleCount > 0) area / triangleCount else 0.0
    cell = sqrt(2 * mean)
    if (!(cell > 0.0)) cell = max(max(width, height), 1.0)
    val limit = 4.0 * max(triangleCount, 1)
    while ((floor(width / cell) + 1) * (floor(height / cell) + 1) > limit) cell *= 1.5
    nx = floor(width / cell).toInt() + 1
    ny = floor(height / cell).toInt() + 1
    val start = IntArray(nx * ny + 1)
    for (t in 0 until triangleCount) {
      if (isDegenerate(t)) continue
      forCells(t) { c -> start[c + 1]++ }
    }
    for (c in 0 until nx * ny) start[c + 1] += start[c]
    val fill = start.copyOf(nx * ny)
    val list = IntArray(start[nx * ny])
    for (t in 0 until triangleCount) {
      if (isDegenerate(t)) continue
      forCells(t) { c -> list[fill[c]++] = t }
    }
    cellStart = start
    cellTriangles = list
  }

  // ячейки габарита треугольника; inline — без объекта-лямбды
  private inline fun forCells(t: Int, action: (Int) -> Unit) {
    val a = triangles[t * 3] * 3
    val b = triangles[t * 3 + 1] * 3
    val c = triangles[t * 3 + 2] * 3
    val x0 = cellX(min(coords[a], min(coords[b], coords[c])))
    val x1 = cellX(max(coords[a], max(coords[b], coords[c])))
    val y0 = cellY(min(coords[a + 1], min(coords[b + 1], coords[c + 1])))
    val y1 = cellY(max(coords[a + 1], max(coords[b + 1], coords[c + 1])))
    for (iy in y0..y1) for (ix in x0..x1) action(iy * nx + ix)
  }

  private fun cellX(x: Double) = floor((x - originX) / cell).toInt().coerceIn(0, nx - 1)
  private fun cellY(y: Double) = floor((y - originY) / cell).toInt().coerceIn(0, ny - 1)

  // удвоенная площадь в плане со знаком (+ — против часовой)
  private fun area2(t: Int): Double {
    val a = triangles[t * 3] * 3
    val b = triangles[t * 3 + 1] * 3
    val c = triangles[t * 3 + 2] * 3
    return (coords[b] - coords[a]) * (coords[c + 1] - coords[a + 1]) - (coords[b + 1] - coords[a + 1]) * (coords[c] - coords[a])
  }

  // площадь — шум округления относительно сторон: такой треугольник в индекс не идёт
  private fun isDegenerate(t: Int): Boolean {
    val a = triangles[t * 3] * 3
    val b = triangles[t * 3 + 1] * 3
    val c = triangles[t * 3 + 2] * 3
    val ux = coords[b] - coords[a]
    val uy = coords[b + 1] - coords[a + 1]
    val vx = coords[c] - coords[a]
    val vy = coords[c + 1] - coords[a + 1]
    return abs(ux * vy - uy * vx) <= FLAT * (ux * ux + uy * uy + vx * vx + vy * vy)
  }

  /** Барицентрика точки в треугольнике [t]: точка внутри (с допуском), если все три не меньше −[EPS]. */
  private fun contains(t: Int, px: Double, py: Double): Boolean = worstEdge(t, px, py) < 0

  // ребро, за которым точка дальше всего (номер k: от вершины k к k+1); −1 — точка внутри
  private fun worstEdge(t: Int, px: Double, py: Double): Int {
    val s = area2(t)
    var worst = -1
    var min = -EPS
    for (k in 0..2) {
      val a = triangles[t * 3 + k] * 3
      val b = triangles[t * 3 + (k + 1) % 3] * 3
      val w = ((coords[b] - coords[a]) * (py - coords[a + 1]) - (coords[b + 1] - coords[a + 1]) * (px - coords[a])) / s
      if (w < min) {
        min = w
        worst = k
      }
    }
    return worst
  }

  /** Треугольник с точкой ([px], [py]) или −1 — точка вне поверхности. Начинает с [hint]. */
  fun locate(px: Double, py: Double): Int {
    ensureIndex()
    var t = hint
    var steps = 0
    while (t >= 0 && steps++ < WALK_STEPS) {
      if (isDegenerate(t)) break
      val k = worstEdge(t, px, py)
      if (k < 0) {
        hint = t
        return t
      }
      t = neighbors[t * 3 + k]
    }
    if (px < originX || py < originY || px > mesh.maxX || py > mesh.maxY) return -1
    val c = cellY(py) * nx + cellX(px)
    for (j in cellStart[c] until cellStart[c + 1]) {
      val u = cellTriangles[j]
      if (contains(u, px, py)) {
        hint = u
        return u
      }
    }
    return -1
  }

  /** Высота плоскости треугольника [t] в точке ([px], [py]). */
  fun height(t: Int, px: Double, py: Double): Double {
    val a = triangles[t * 3] * 3
    val b = triangles[t * 3 + 1] * 3
    val c = triangles[t * 3 + 2] * 3
    val s = area2(t)
    val wa = ((coords[c] - coords[b]) * (py - coords[b + 1]) - (coords[c + 1] - coords[b + 1]) * (px - coords[b])) / s
    val wb = ((coords[a] - coords[c]) * (py - coords[c + 1]) - (coords[a + 1] - coords[c + 1]) * (px - coords[c])) / s
    return wa * coords[a + 2] + wb * coords[b + 2] + (1 - wa - wb) * coords[c + 2]
  }

  fun neighbor(t: Int, edge: Int): Int = neighbors[t * 3 + edge]

  /**
   * Часть отрезка P(s) = (ax, ay) + s · (dx, dy) внутри треугольника [t]: [clipLo]..[clipHi] и ребро выхода [clipExit].
   * Пусто — clipLo > clipHi. Вырожденный треугольник — всегда пусто.
   */
  fun clip(t: Int, ax: Double, ay: Double, dx: Double, dy: Double) {
    var lo = Double.NEGATIVE_INFINITY
    var hi = Double.POSITIVE_INFINITY
    var exit = -1
    if (isDegenerate(t)) {
      clipLo = 1.0
      clipHi = 0.0
      clipExit = -1
      return
    }
    val s = area2(t)
    for (k in 0..2) {
      val a = triangles[t * 3 + k] * 3
      val b = triangles[t * 3 + (k + 1) % 3] * 3
      val ex = coords[b] - coords[a]
      val ey = coords[b + 1] - coords[a + 1]
      // барицентрика на ребре k вдоль отрезка: w(s) = w0 + s · wd ≥ −EPS
      val w0 = (ex * (ay - coords[a + 1]) - ey * (ax - coords[a])) / s + EPS
      val wd = (ex * dy - ey * dx) / s
      if (wd > 0) {
        lo = max(lo, -w0 / wd)
      } else if (wd < 0) {
        val at = -w0 / wd
        if (at < hi) {
          hi = at
          exit = k
        }
      } else if (w0 < 0) {
        lo = 1.0
        hi = 0.0
      }
    }
    clipLo = lo
    clipHi = hi
    clipExit = exit
  }

  /**
   * Первый треугольник, в который отрезок входит после параметра [from] (до [to]): клетки сетки по ходу отрезка
   * (обход Амантидеса — Ву). Результат — [clipLo] = параметр входа, возвращает треугольник или −1.
   */
  fun enter(ax: Double, ay: Double, dx: Double, dy: Double, from: Double, to: Double): Int {
    ensureIndex()
    // отрезок, обрезанный габаритом сетки
    var u0 = from
    var u1 = to
    val maxX = originX + nx * cell
    val maxY = originY + ny * cell
    if (dx == 0.0) {
      if (ax < originX || ax > maxX) return -1
    } else {
      val p = (originX - ax) / dx
      val q = (maxX - ax) / dx
      u0 = max(u0, min(p, q))
      u1 = min(u1, max(p, q))
    }
    if (dy == 0.0) {
      if (ay < originY || ay > maxY) return -1
    } else {
      val p = (originY - ay) / dy
      val q = (maxY - ay) / dy
      u0 = max(u0, min(p, q))
      u1 = min(u1, max(p, q))
    }
    if (u0 > u1) return -1
    var ix = cellX(ax + dx * u0)
    var iy = cellY(ay + dy * u0)
    val stepX = if (dx > 0) 1 else -1
    val stepY = if (dy > 0) 1 else -1
    var nextX = if (dx > 0) (originX + (ix + 1) * cell - ax) / dx else if (dx < 0) (originX + ix * cell - ax) / dx else Double.POSITIVE_INFINITY
    var nextY = if (dy > 0) (originY + (iy + 1) * cell - ay) / dy else if (dy < 0) (originY + iy * cell - ay) / dy else Double.POSITIVE_INFINITY
    val deltaX = if (dx != 0.0) cell / abs(dx) else Double.POSITIVE_INFINITY
    val deltaY = if (dy != 0.0) cell / abs(dy) else Double.POSITIVE_INFINITY
    var best = -1
    var bestAt = Double.POSITIVE_INFINITY
    var bestHi = Double.NEGATIVE_INFINITY
    while (true) {
      val c = iy * nx + ix
      for (j in cellStart[c] until cellStart[c + 1]) {
        val t = cellTriangles[j]
        clip(t, ax, ay, dx, dy)
        if (clipLo > clipHi || clipHi <= from + PARAM_EPS || clipLo >= to) continue
        val at = max(clipLo, from)
        if (at < bestAt - PARAM_EPS || (at <= bestAt + PARAM_EPS && clipHi > bestHi)) {
          best = t
          bestAt = at
          bestHi = clipHi
        }
      }
      val leave = min(nextX, nextY)
      if (bestAt <= leave || leave >= u1) break
      if (nextX < nextY) {
        ix += stepX
        nextX += deltaX
      } else {
        iy += stepY
        nextY += deltaY
      }
      if (ix < 0 || iy < 0 || ix >= nx || iy >= ny) break
    }
    if (best < 0) return -1
    clip(best, ax, ay, dx, dy)
    clipLo = bestAt
    return best
  }

  companion object {
    /** Допуск барицентрики: точка на ребре — в обоих треугольниках. */
    const val EPS = 1e-12
    /** Допуск параметра вдоль отрезка. */
    const val PARAM_EPS = 1e-12
    // щель как в SurfacesSection.FLAT
    private const val FLAT = 1e-9
    // шагов по соседям до перехода к сетке
    private const val WALK_STEPS = 64
  }
}

/**
 * Посадка 2D-отрезков на поверхности [surfaces] (по убыванию площади): отрезок дробится по рёбрам треугольников
 * и получает отметки рельефа; часть вне первой поверхности сажается на следующую, вне всех — остаётся плоской.
 * Куски пишутся в [out] семёрками: x1 y1 z1 x2 y2 z2 и признак (1 — на поверхности, 0 — плоский).
 */
internal class LandXmlDrape(private val surfaces: List<DrapeSurface>) {
  val out = GrowingDoubleArray()

  /** Сколько кусков отрезка пришлось оборвать по пределу (кусков больше, чем рёбер у поверхности). */
  var cut = 0
    private set

  private var ax = 0.0
  private var ay = 0.0
  private var az = 0.0
  private var dx = 0.0
  private var dy = 0.0
  private var dz = 0.0

  /** Высота точки на первой поверхности, где она лежит; NaN — вне всех. */
  fun height(px: Double, py: Double): Double {
    for (surface in surfaces) {
      val t = surface.locate(px, py)
      if (t >= 0) return surface.height(t, px, py)
    }
    return Double.NaN
  }

  /** Отрезок (x1, y1, z1)–(x2, y2, z2) — куски в [out] (дописываются). */
  fun segment(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double) {
    ax = x1
    ay = y1
    az = z1
    dx = x2 - x1
    dy = y2 - y1
    dz = z2 - z1
    if (dx == 0.0 && dy == 0.0) {
      // точка: нечего дробить
      val h = height(x1, y1)
      if (h.isNaN()) piece(0.0, 1.0, z1, z2, false) else piece(0.0, 1.0, h, h, true)
      return
    }
    range(0, 0.0, 1.0)
  }

  // часть отрезка [from, to] на поверхности [index]; вне её — на следующую
  private fun range(index: Int, from: Double, to: Double) {
    if (index >= surfaces.size) {
      piece(from, to, az + dz * from, az + dz * to, false)
      return
    }
    val surface = surfaces[index]
    var s = from
    var t = surface.locate(ax + dx * s, ay + dy * s)
    if (t >= 0) {
      surface.clip(t, ax, ay, dx, dy)
      if (surface.clipHi <= s + DrapeSurface.PARAM_EPS) t = -1
    }
    var pieces = 0
    val limit = surface.edgeCount + 2
    while (s < to - DrapeSurface.PARAM_EPS) {
      if (t < 0) {
        t = surface.enter(ax, ay, dx, dy, s, to)
        if (t < 0) {
          range(index + 1, s, to)
          return
        }
        val at = surface.clipLo
        if (at > s + DrapeSurface.PARAM_EPS) {
          range(index + 1, s, at)
          s = at
        }
      }
      if (++pieces > limit) {
        // предел: остаток — хордой, как был
        cut++
        range(index + 1, s, to)
        return
      }
      val e = min(surface.clipHi, to)
      val x1 = ax + dx * s
      val y1 = ay + dy * s
      val x2 = ax + dx * e
      val y2 = ay + dy * e
      piece(s, e, surface.height(t, x1, y1), surface.height(t, x2, y2), true)
      s = e
      if (s >= to - DrapeSurface.PARAM_EPS) {
        surface.hint = t
        break
      }
      val exit = surface.clipExit
      val next = if (exit >= 0) surface.neighbor(t, exit) else -1
      surface.hint = t
      t = next
      if (t >= 0) {
        surface.clip(t, ax, ay, dx, dy)
        // вышли через вершину или в вырожденный треугольник — найти дальше по сетке
        if (surface.clipHi <= s + DrapeSurface.PARAM_EPS || surface.clipLo > s + DrapeSurface.PARAM_EPS) t = -1
      }
    }
  }

  private fun piece(from: Double, to: Double, z1: Double, z2: Double, onSurface: Boolean) {
    out.add(ax + dx * from)
    out.add(ay + dy * from)
    out.add(z1)
    out.add(ax + dx * to)
    out.add(ay + dy * to)
    out.add(z2)
    out.add(if (onSurface) 1.0 else 0.0)
  }
}
