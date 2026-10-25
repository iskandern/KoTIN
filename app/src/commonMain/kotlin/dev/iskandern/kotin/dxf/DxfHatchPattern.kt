package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.toRadians
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Штриховка HATCH: каждое семейство линий паттерна пересекается с контурами, участки внутри (по чётности пересечений —
 * отверстия и островки как у заливки) режутся на штрихи. Фаза штрихов отсчитывается от начала своей линии
 * (база + k × смещение), как в AutoCAD. Без зависимостей, работает в commonMain.
 */
internal object HatchPatternClipper {

  /**
   * [loops] — замкнутые контуры в OCS (пары x, y без повтора первой точки). Результат — отрезки x1 y1 x2 y2 подряд;
   * null — штриховка слишком частая: шаг линий меньше [minSpacing], линий больше [MAX_LINES] или отрезков больше [maxSegments].
   */
  fun clip(loops: List<DoubleArray>, lines: List<HatchPatternLine>, minSpacing: Double, maxSegments: Int): DoubleArray? {
    val families = ArrayList<Family>()
    var lineCount = 0
    var estimate = 0.0
    for (line in lines) {
      val family = family(line, loops, minSpacing) ?: return null
      if (family.chords.isEmpty()) continue
      lineCount += family.chords.size
      if (lineCount > MAX_LINES) return null
      estimate += family.segmentEstimate()
      if (estimate > maxSegments) return null
      families.add(family)
    }
    val out = GrowingDoubleArray()
    for (family in families) family.emit(out)
    return out.toArray()
  }

  /** Линии стандартного паттерна (acadiso.pat) с поворотом и масштабом хэтча — если в файле нет своих (код 78 = 0). */
  fun standardLines(pattern: HatchPattern): List<HatchPatternLine> {
    val rows = STANDARD[pattern.name.uppercase()] ?: return emptyList()
    val scale = if (pattern.scale > 0) pattern.scale else 1.0
    val rotation = toRadians(pattern.angleDeg)
    return rows.map { r ->
      // в .pat смещение (сдвиг вдоль, шаг поперёк) — в системе линии, в HATCH — уже повёрнутый вектор
      val angle = rotation + toRadians(r[0])
      HatchPatternLine(
        angleDeg = pattern.angleDeg + r[0],
        baseX = (r[1] * cos(rotation) - r[2] * sin(rotation)) * scale,
        baseY = (r[1] * sin(rotation) + r[2] * cos(rotation)) * scale,
        offsetX = (r[3] * cos(angle) - r[4] * sin(angle)) * scale,
        offsetY = (r[3] * sin(angle) + r[4] * cos(angle)) * scale,
        dashes = DoubleArray(r.size - 5) { r[5 + it] * scale },
      )
    }
  }

  /**
   * Пересечения линий семейства с контурами. null — шаг меньше [minSpacing] (слишком часто); вырожденное
   * семейство (нулевой шаг) — без линий.
   */
  private fun family(line: HatchPatternLine, loops: List<DoubleArray>, minSpacing: Double): Family? {
    val angle = toRadians(line.angleDeg)
    val dx = cos(angle)
    val dy = sin(angle)
    // нормаль к линиям: n — расстояние от базы поперёк, t — вдоль
    val nx = -dy
    val ny = dx
    var ox = line.offsetX
    var oy = line.offsetY
    var spacing = ox * nx + oy * ny
    val empty = Family(line, dx, dy, 0.0, 0.0, 0L, emptyArray())
    if (!(abs(spacing) > 0) || !spacing.isFinite()) return empty
    if (spacing < 0) {
      ox = -ox; oy = -oy; spacing = -spacing
    }
    if (spacing < minSpacing) return null
    val shift = ox * dx + oy * dy

    var nMin = Double.MAX_VALUE
    var nMax = -Double.MAX_VALUE
    for (xy in loops) {
      for (i in 0 until xy.size / 2) {
        val n = (xy[i * 2] - line.baseX) * nx + (xy[i * 2 + 1] - line.baseY) * ny
        if (n < nMin) nMin = n
        if (n > nMax) nMax = n
      }
    }
    val firstD = ceil(nMin / spacing)
    val lastD = floor(nMax / spacing)
    if (lastD - firstD + 1 > MAX_LINES) return null
    // номер линии — Long: у координат в МСК (~10⁷) при мелком шаге он выходит за Int
    val first = firstD.toLong()
    val count = (lastD - firstD).toInt() + 1
    if (count <= 0) return empty

    // по ребру — все линии, которые оно пересекает; ребро пересекает линию c, если концы по разные стороны:
    // (na > c) != (nb > c) — одно и то же правило для общей вершины двух рёбер, без двойного счёта
    val hits = arrayOfNulls<GrowingDoubleArray>(count)
    for (xy in loops) {
      val n = xy.size / 2
      for (i in 0 until n) {
        val j = (i + 1) % n
        val ax = xy[i * 2] - line.baseX
        val ay = xy[i * 2 + 1] - line.baseY
        val bx = xy[j * 2] - line.baseX
        val by = xy[j * 2 + 1] - line.baseY
        val na = ax * nx + ay * ny
        val nb = bx * nx + by * ny
        if (na == nb) continue
        val ta = ax * dx + ay * dy
        val tb = bx * dx + by * dy
        // диапазон с запасом в одну линию, точная проверка — тем же сравнением
        val from = maxOf(first, floor(minOf(na, nb) / spacing).toLong() - 1)
        val to = minOf(first + count - 1, ceil(maxOf(na, nb) / spacing).toLong() + 1)
        for (k in from..to) {
          val c = k * spacing
          if ((na > c) == (nb > c)) continue
          val t = ta + (tb - ta) * ((c - na) / (nb - na)) - k * shift
          val index = (k - first).toInt()
          (hits[index] ?: GrowingDoubleArray().also { hits[index] = it }).add(t)
        }
      }
    }
    val chords = Array(count) { k ->
      val values = hits[k]?.toArray() ?: DoubleArray(0)
      values.sort()
      // нечётное число пересечений бывает только от погрешности — лишнее отбрасываем
      if (values.size % 2 == 0) values else values.copyOf(values.size - 1)
    }
    return Family(line, dx, dy, ox, oy, first, chords)
  }

  /**
   * Семейство линий: направление ([dx], [dy]), смещение между соседними линиями ([ox], [oy]) и по линии
   * (номер [first] + индекс) отсортированные пары t0, t1 участков внутри контура, t — от начала линии.
   */
  private class Family(
    val line: HatchPatternLine,
    val dx: Double, val dy: Double,
    val ox: Double, val oy: Double,
    val first: Long,
    val chords: Array<DoubleArray>,
  ) {
    private val period = line.dashes.sumOf { abs(it) }
    private val dashed = line.dashes.isNotEmpty() && period > 0 && period.isFinite()
    // «точка» паттерна — короткий штрих, иначе её не видно
    private val dot = period * DOT_FRACTION

    fun segmentEstimate(): Double {
      var sum = 0.0
      val drawn = line.dashes.count { it >= 0 }
      for (pairs in chords) {
        for (p in 0 until pairs.size / 2) {
          sum += if (dashed) ((pairs[p * 2 + 1] - pairs[p * 2]) / period + 1) * drawn else 1.0
        }
      }
      return sum
    }

    fun emit(out: GrowingDoubleArray) {
      for ((index, pairs) in chords.withIndex()) {
        val k = first + index
        // начало линии k — от него идёт фаза штрихов
        val originX = line.baseX + k * ox
        val originY = line.baseY + k * oy
        for (p in 0 until pairs.size / 2) {
          val t0 = pairs[p * 2]
          val t1 = pairs[p * 2 + 1]
          if (t1 <= t0) continue
          if (!dashed) {
            segment(out, originX, originY, t0, t1)
            continue
          }
          var start = floor(t0 / period) * period
          while (start < t1) {
            var position = start
            for (e in line.dashes) {
              if (position >= t1) break
              when {
                e > 0 -> clipped(out, originX, originY, position, position + e, t0, t1)
                e == 0.0 -> clipped(out, originX, originY, position, position + dot, t0, t1)
              }
              position += abs(e)
            }
            // период меньше точности координаты — дальше не продвинуться
            if (start + period == start) break
            start += period
          }
        }
      }
    }

    private fun clipped(out: GrowingDoubleArray, ox: Double, oy: Double, a: Double, b: Double, t0: Double, t1: Double) {
      val lo = maxOf(a, t0)
      val hi = minOf(b, t1)
      if (hi > lo) segment(out, ox, oy, lo, hi)
    }

    private fun segment(out: GrowingDoubleArray, ox: Double, oy: Double, a: Double, b: Double) {
      out.add(ox + dx * a); out.add(oy + dy * a)
      out.add(ox + dx * b); out.add(oy + dy * b)
    }
  }

  // стандартные паттерны acadiso.pat (мм): угол, база x, y, сдвиг вдоль линии, шаг поперёк, штрихи
  private val STANDARD = mapOf(
    "ANSI31" to listOf(doubleArrayOf(45.0, 0.0, 0.0, 0.0, 3.175)),
    "ANSI37" to listOf(doubleArrayOf(45.0, 0.0, 0.0, 0.0, 3.175), doubleArrayOf(135.0, 0.0, 0.0, 0.0, 3.175)),
    "DOTS" to listOf(doubleArrayOf(0.0, 0.0, 0.0, 0.79375, 1.5875, 0.0, -1.5875)),
  )

  // больше линий на хэтч не строим — штриховка слилась бы в тон
  const val MAX_LINES = 20_000
  // длина «точки» относительно периода паттерна
  private const val DOT_FRACTION = 0.1
}

/**
 * Градиент HATCH: цвет на каждой вершине треугольников заливки. Параметр градиента t (0 — первый цвет, 1 — второй)
 * считается в системе, повёрнутой на угол градиента, по габаритам контуров. LINEAR линеен — хватает вершин исходных
 * треугольников; для остальных типов треугольники режутся сеткой [GRID] × [GRID] по нужным осям.
 */
internal object HatchGradientShader {

  /** [triangles] — x1 y1 x2 y2 x3 y3 подряд. Результат — треугольники (их может стать больше) и цвет 0xRRGGBB на вершину. */
  fun shade(triangles: DoubleArray, loops: List<DoubleArray>, gradient: HatchGradient): Pair<DoubleArray, IntArray> {
    val ux = cos(gradient.angle)
    val uy = sin(gradient.angle)
    // u — вдоль направления градиента, v — поперёк
    var uMin = Double.MAX_VALUE; var uMax = -Double.MAX_VALUE
    var vMin = Double.MAX_VALUE; var vMax = -Double.MAX_VALUE
    for (xy in loops) {
      for (i in 0 until xy.size / 2) {
        val u = xy[i * 2] * ux + xy[i * 2 + 1] * uy
        val v = -xy[i * 2] * uy + xy[i * 2 + 1] * ux
        uMin = minOf(uMin, u); uMax = maxOf(uMax, u)
        vMin = minOf(vMin, v); vMax = maxOf(vMax, v)
      }
    }
    val du = (uMax - uMin).takeIf { it > 0 } ?: 1.0
    val dv = (vMax - vMin).takeIf { it > 0 } ?: 1.0
    val type = gradient.name
    val splitU = type in ALONG_U || type in RADIAL
    val splitV = type in ALONG_V || type in RADIAL

    fun t(x: Double, y: Double): Double {
      val s = (x * ux + y * uy - uMin) / du
      val r = (-x * uy + y * ux - vMin) / dv
      val value = when (type) {
        "CYLINDER" -> 1 - (2 * s - 1) * (2 * s - 1)
        "INVCYLINDER" -> (2 * s - 1) * (2 * s - 1)
        "CURVED" -> 1 - (1 - r) * (1 - r)
        "INVCURVED" -> (1 - r) * (1 - r)
        // сферический — от центра габаритов, полусферический — от середины нижнего края
        "SPHERICAL" -> 1 - hypot((s - 0.5) * du, (r - 0.5) * dv) / (hypot(du, dv) / 2)
        "INVSPHERICAL" -> hypot((s - 0.5) * du, (r - 0.5) * dv) / (hypot(du, dv) / 2)
        "HEMISPHERICAL" -> 1 - hypot((s - 0.5) * du, r * dv) / hypot(du / 2, dv)
        "INVHEMISPHERICAL" -> hypot((s - 0.5) * du, r * dv) / hypot(du / 2, dv)
        else -> s
      }
      return value.coerceIn(0.0, 1.0)
    }

    val out = GrowingDoubleArray()
    val corners = DoubleArray(6)
    for (i in 0 until triangles.size / 6) {
      triangles.copyInto(corners, 0, i * 6, i * 6 + 6)
      if (!splitU && !splitV) {
        for (value in corners) out.add(value)
        continue
      }
      forEachCell(corners, ux, uy, uMin, du / GRID, splitU) { stripe ->
        forEachCell(stripe, -uy, ux, vMin, dv / GRID, splitV) { cell ->
          // выпуклый кусок — веером
          for (k in 2 until cell.size / 2) {
            out.add(cell[0]); out.add(cell[1])
            out.add(cell[k * 2 - 2]); out.add(cell[k * 2 - 1])
            out.add(cell[k * 2]); out.add(cell[k * 2 + 1])
          }
        }
      }
    }
    val xy = out.toArray()
    val colors = IntArray(xy.size / 2) { mix(gradient.color1, gradient.color2, t(xy[it * 2], xy[it * 2 + 1])) }
    return xy to colors
  }

  /** Режет выпуклый многоугольник полосами шириной [step] вдоль оси (ax, ay), начиная с [origin]; без [split] — целиком. */
  private inline fun forEachCell(poly: DoubleArray, ax: Double, ay: Double, origin: Double, step: Double, split: Boolean, action: (DoubleArray) -> Unit) {
    if (!split) {
      action(poly)
      return
    }
    var lo = Double.MAX_VALUE
    var hi = -Double.MAX_VALUE
    for (i in 0 until poly.size / 2) {
      val d = poly[i * 2] * ax + poly[i * 2 + 1] * ay
      lo = minOf(lo, d); hi = maxOf(hi, d)
    }
    val first = floor((lo - origin) / step).toInt()
    val last = floor((hi - origin) / step).toInt()
    if (first >= last) {
      action(poly)
      return
    }
    for (cell in first..last) {
      var piece = poly
      if (cell > first) piece = clipHalfPlane(piece, ax, ay, origin + cell * step, keepAbove = true)
      if (cell < last) piece = clipHalfPlane(piece, ax, ay, origin + (cell + 1) * step, keepAbove = false)
      if (piece.size >= 6) action(piece)
    }
  }

  /** Часть выпуклого многоугольника по одну сторону от прямой p·a = c (Сазерленд — Ходжмен). */
  private fun clipHalfPlane(poly: DoubleArray, ax: Double, ay: Double, c: Double, keepAbove: Boolean): DoubleArray {
    val out = GrowingDoubleArray()
    val n = poly.size / 2
    for (i in 0 until n) {
      val j = (i + 1) % n
      val di = (poly[i * 2] * ax + poly[i * 2 + 1] * ay - c).let { if (keepAbove) it else -it }
      val dj = (poly[j * 2] * ax + poly[j * 2 + 1] * ay - c).let { if (keepAbove) it else -it }
      if (di >= 0) { out.add(poly[i * 2]); out.add(poly[i * 2 + 1]) }
      if ((di >= 0) != (dj >= 0)) {
        val f = di / (di - dj)
        out.add(poly[i * 2] + (poly[j * 2] - poly[i * 2]) * f)
        out.add(poly[i * 2 + 1] + (poly[j * 2 + 1] - poly[i * 2 + 1]) * f)
      }
    }
    return out.toArray()
  }

  private fun mix(a: Int, b: Int, t: Double): Int {
    fun channel(shift: Int): Int {
      val ca = (a shr shift) and 0xFF
      val cb = (b shr shift) and 0xFF
      return ((ca + (cb - ca) * t) + 0.5).toInt().coerceIn(0, 255) shl shift
    }
    return channel(16) or channel(8) or channel(0)
  }

  private val ALONG_U = setOf("CYLINDER", "INVCYLINDER")
  private val ALONG_V = setOf("CURVED", "INVCURVED")
  private val RADIAL = setOf("SPHERICAL", "INVSPHERICAL", "HEMISPHERICAL", "INVHEMISPHERICAL")
  // полос на габарит для нелинейных градиентов
  private const val GRID = 16
}
