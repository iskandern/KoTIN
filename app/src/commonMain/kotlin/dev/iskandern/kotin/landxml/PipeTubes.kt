package dev.iskandern.kotin.landxml

import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.RawMeshBuilder
import dev.iskandern.kotin.model.SceneMesh
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Сечение трубы: точки ([u] — вбок вправо по ходу, [v] — вверх от оси, в метрах) и грани — пары номеров точек.
 * Гладкое сечение (круг, эллипс) — точки общие у соседних граней; у прямоугольника и лотка у каждой грани свои точки.
 */
internal class PipeProfile(val u: DoubleArray, val v: DoubleArray, val edges: IntArray) {
  val faceCount: Int get() = edges.size / 2

  /** Гладкое: в каркас — только кольца на концах, продольные рёбра граней круга сплошь закрыли бы тонкую трубу. */
  val smooth: Boolean get() = edges.size == u.size * 2

  companion object {
    /** Эллипс [n] граней с полуосями [halfWidth] вбок и [halfHeight] вверх (круг — равные полуоси). */
    fun ellipse(n: Int, halfWidth: Double, halfHeight: Double): PipeProfile {
      val (unitCos, unitSin) = unitRing(n)
      return PipeProfile(
        DoubleArray(n) { halfWidth * unitCos[it] }, DoubleArray(n) { halfHeight * unitSin[it] },
        IntArray(n * 2) { if (it % 2 == 0) it / 2 else (it / 2 + 1) % n },
      )
    }

    /** Прямоугольник: четыре плоские стенки. */
    fun rect(height: Double, width: Double): PipeProfile {
      val w = width / 2
      val h = height / 2
      return faces(doubleArrayOf(w, -w, -w, -w, -w, w, w, w), doubleArrayOf(h, h, h, -h, -h, -h, -h, h))
    }

    /** Лоток — трапеция без крышки: левая стенка, дно, правая стенка. */
    fun channel(height: Double, widthTop: Double, widthBottom: Double): PipeProfile {
      val t = widthTop / 2
      val b = widthBottom / 2
      val h = height / 2
      return faces(doubleArrayOf(-t, -b, -b, b, b, t), doubleArrayOf(h, -h, -h, -h, -h, h))
    }

    // плоские грани: точки парами (2k, 2k + 1)
    private fun faces(u: DoubleArray, v: DoubleArray) = PipeProfile(u, v, IntArray(u.size) { it })

    // единичное кольцо — шаблон на все сечения с тем же числом граней
    private val rings = HashMap<Int, Pair<DoubleArray, DoubleArray>>()

    private fun unitRing(n: Int): Pair<DoubleArray, DoubleArray> = rings.getOrPut(n) {
      DoubleArray(n) { cos(2 * PI * it / n) } to DoubleArray(n) { sin(2 * PI * it / n) }
    }
  }
}

/**
 * Трубы одного слоя одной сеткой: сечение ведётся вдоль ломаной оси, на каждую точку оси — кольцо вершин
 * в плоскости, перпендикулярной оси; торцы открыты. Вершины и треугольники — сразу в растущие массивы.
 * Каркас: кольца на концах и, у плоских стенок, продольные рёбра.
 */
internal class PipeTubes {
  private val mesh = RawMeshBuilder()

  val triangleCount: Int get() = mesh.triangleCount

  /**
   * Труба сечения [profile] по оси [axis] (x, y, z подряд, [points] точек; ось — центр сечения).
   * false — ось вырождена (меньше двух точек или вертикальный участок): трубы нет.
   */
  fun add(profile: PipeProfile, axis: GrowingDoubleArray, points: Int, id: Int): Boolean {
    if (points < 2) return false
    // направления участков, единичные
    val segments = DoubleArray((points - 1) * 3)
    for (i in 0 until points - 1) {
      val dx = axis[i * 3 + 3] - axis[i * 3]
      val dy = axis[i * 3 + 4] - axis[i * 3 + 1]
      val dz = axis[i * 3 + 5] - axis[i * 3 + 2]
      val l = sqrt(dx * dx + dy * dy + dz * dz)
      if (l == 0.0) return false
      segments[i * 3] = dx / l
      segments[i * 3 + 1] = dy / l
      segments[i * 3 + 2] = dz / l
    }
    // касательная внутри — среднее соседних участков (у дуги — касательная к окружности); на конце — соседняя,
    // отражённая относительно крайнего участка (у дуги хорда делит угол между касательными концов пополам)
    val tangents = DoubleArray(points * 3)
    for (i in 1 until points - 1) for (k in 0 until 3) tangents[i * 3 + k] = segments[i * 3 - 3 + k] + segments[i * 3 + k]
    for (i in 1 until points - 1) normalize(tangents, i)
    if (points == 2) {
      for (k in 0 until 3) {
        tangents[k] = segments[k]
        tangents[3 + k] = segments[k]
      }
    } else {
      reflect(tangents, 1, segments, 0, 0)
      reflect(tangents, points - 2, segments, points - 2, points - 1)
    }
    for (i in 0 until points) {
      if (hypot(tangents[i * 3], tangents[i * 3 + 1]) <= 1e-9) return false
    }

    mesh.markObject(id)
    val size = profile.u.size
    val first = mesh.vertexCount
    for (i in 0 until points) {
      val tx = tangents[i * 3]
      val ty = tangents[i * 3 + 1]
      val tz = tangents[i * 3 + 2]
      // вбок — горизонтально вправо по ходу, вверх — перпендикулярно оси и боку
      val h = hypot(tx, ty)
      val sx = ty / h
      val sy = -tx / h
      val ux = sy * tz
      val uy = -sx * tz
      val uz = sx * ty - sy * tx
      val cx = axis[i * 3]
      val cy = axis[i * 3 + 1]
      val cz = axis[i * 3 + 2]
      for (k in 0 until size) {
        val u = profile.u[k]
        val v = profile.v[k]
        mesh.vertex(cx + sx * u + ux * v, cy + sy * u + uy * v, cz + uz * v)
      }
    }
    val faces = profile.edges
    val along = if (profile.smooth) 0 else 1
    for (i in 0 until points - 1) {
      val ring = first + i * size
      val next = ring + size
      val startMask = if (i == 0) EDGE_AB else 0
      val endMask = if (i == points - 2) EDGE_BC else 0
      for (f in 0 until profile.faceCount) {
        val a = faces[f * 2]
        val b = faces[f * 2 + 1]
        // рёбра: в первом ab — кольцо начала, bc — продольное; во втором bc — кольцо конца, ca — продольное
        mesh.triangle(ring + a, ring + b, next + b, startMask or EDGE_BC * along)
        mesh.triangle(ring + a, next + b, next + a, endMask or EDGE_CA * along)
      }
    }
    return true
  }

  private fun normalize(v: DoubleArray, i: Int) {
    val l = sqrt(v[i * 3] * v[i * 3] + v[i * 3 + 1] * v[i * 3 + 1] + v[i * 3 + 2] * v[i * 3 + 2])
    if (l == 0.0) return // излом назад — касательной нет, точка отсеется проверкой на горизонталь
    for (k in 0 until 3) v[i * 3 + k] /= l
  }

  // касательная [to] = касательная [from], отражённая относительно участка [segment]: 2 (s·t) s − t
  private fun reflect(tangents: DoubleArray, from: Int, segments: DoubleArray, segment: Int, to: Int) {
    var dot = 0.0
    for (k in 0 until 3) dot += segments[segment * 3 + k] * tangents[from * 3 + k]
    for (k in 0 until 3) tangents[to * 3 + k] = 2 * dot * segments[segment * 3 + k] - tangents[from * 3 + k]
  }


  fun build(layer: String): SceneMesh = mesh.build(layer, structure = true)

  private companion object {
    const val EDGE_AB = 1
    const val EDGE_BC = 2
    const val EDGE_CA = 4
  }
}
