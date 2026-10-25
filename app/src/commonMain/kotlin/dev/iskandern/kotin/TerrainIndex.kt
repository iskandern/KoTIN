package dev.iskandern.kotin

import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.RenderSurface
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Рельеф под лучом — опора жестов карты ([SceneCameraController.anchorAt]): только настоящие поверхности (TIN), не
 * трубы и не чертёж. TIN — поверхность высот, поэтому треугольники раскладываются по двумерной сетке в плане, и луч
 * проверяет только ячейки под своей проекцией. Сетка не зависит от вертикального масштаба (он лишь растягивает Y) и
 * строится один раз на сцену ([build], в фоне); до неё небольшие поверхности перебираются целиком.
 */
class TerrainIndex(scene: RenderScene) {
  private val surfaces: List<RenderSurface> = scene.surfaces.filter { !it.structure && it.triangleCount > 0 }

  // начало треугольников каждой поверхности в сквозной нумерации
  private val offsets = IntArray(surfaces.size + 1).also { o ->
    for (i in surfaces.indices) o[i + 1] = o[i] + surfaces[i].triangleCount
  }

  val triangleCount: Int get() = offsets[surfaces.size]

  @Volatile
  private var grid: Grid? = null

  /** Сетка построена (или не нужна: поверхностей нет). */
  val ready: Boolean get() = grid != null || surfaces.isEmpty()

  /** Строит сетку; повторный вызов ничего не делает. Тяжёлое — для 1 млн треугольников десятки мс. */
  fun build() {
    if (!ready) grid = Grid.of(surfaces, offsets)
  }

  /**
   * Расстояние вдоль [ray] до ближайшей точки видимого при [settings] рельефа; null — луч мимо рельефа или рельеф
   * скрыт. Точка попадания — в координатах камеры (Y с вертикальным масштабом).
   */
  fun distance(ray: PickRay, settings: ViewSettings): Double? {
    if (!settings.showSurface || surfaces.isEmpty()) return null
    val visible = BooleanArray(surfaces.size) { surfaces[it].layer !in settings.hiddenLayers }
    if (visible.none { it }) return null
    val k = settings.verticalScale.toDouble()
    val g = grid
    val t = when {
      g != null -> g.hit(ray, k, visible)
      triangleCount <= BRUTE_FORCE_LIMIT -> hitAll(ray, k, visible)
      else -> return null
    }
    return if (t > 0.0) t else null
  }

  /** Высота (с вертикальным масштабом, как у камеры) точки рельефа под [ray], см. [distance]. */
  fun height(ray: PickRay, settings: ViewSettings): Float? =
    distance(ray, settings)?.let { (ray.oy + ray.dy * it).toFloat() }

  /** Перебор всех треугольников: расстояние до ближайшего попадания, −1 — мимо. Без сетки и для сверки с ней. */
  internal fun hitAll(ray: PickRay, k: Double, visible: BooleanArray): Double {
    var best = -1.0
    for ((s, surface) in surfaces.withIndex()) {
      if (!visible[s]) continue
      val p = surface.positions
      val ix = surface.indices
      for (tri in 0 until surface.triangleCount) {
        val t = rayTriangle(ray, k, p, ix[tri * 3] * 3, ix[tri * 3 + 1] * 3, ix[tri * 3 + 2] * 3)
        if (t > 0.0 && (best < 0.0 || t < best)) best = t
      }
    }
    return best
  }

  /** Через сетку; null — сетка ещё не построена. Для тестов. */
  internal fun hitGrid(ray: PickRay, k: Double, visible: BooleanArray): Double? = grid?.hit(ray, k, visible)

  /** Ячейки [nx] × [nz] в плане от ([minX], [minZ]); треугольники ячейки c — [items] от cellStart[c] до cellStart[c + 1]. */
  private class Grid(
    val surfaces: List<RenderSurface>,
    val offsets: IntArray,
    val minX: Double, val minZ: Double, val maxX: Double, val maxZ: Double,
    val nx: Int, val nz: Int,
    val sizeX: Double, val sizeZ: Double,
    val cellStart: IntArray,
    val items: IntArray,
  ) {
    fun cellX(x: Double) = ((x - minX) / sizeX).toInt().coerceIn(0, nx - 1)
    fun cellZ(z: Double) = ((z - minZ) / sizeZ).toInt().coerceIn(0, nz - 1)

    /**
     * Обход ячеек под проекцией луча от ближней к дальней (Amanatides — Woo). Попадание не дальше выхода из текущей
     * ячейки — ближайшее: всё, что ближе, лежит в плане в уже пройденных ячейках.
     */
    fun hit(ray: PickRay, k: Double, visible: BooleanArray): Double {
      // отрезок луча над габаритами сетки в плане
      var t0 = 0.0
      var t1 = Double.POSITIVE_INFINITY
      if (abs(ray.dx) < PARALLEL) {
        if (ray.ox < minX || ray.ox > maxX) return -1.0
      } else {
        val a = (minX - ray.ox) / ray.dx
        val b = (maxX - ray.ox) / ray.dx
        t0 = max(t0, min(a, b)); t1 = min(t1, max(a, b))
      }
      if (abs(ray.dz) < PARALLEL) {
        if (ray.oz < minZ || ray.oz > maxZ) return -1.0
      } else {
        val a = (minZ - ray.oz) / ray.dz
        val b = (maxZ - ray.oz) / ray.dz
        t0 = max(t0, min(a, b)); t1 = min(t1, max(a, b))
      }
      if (t0 > t1) return -1.0

      var cx = cellX(ray.ox + ray.dx * t0)
      var cz = cellZ(ray.oz + ray.dz * t0)
      val stepX = if (ray.dx > 0) 1 else -1
      val stepZ = if (ray.dz > 0) 1 else -1
      val deltaX = if (abs(ray.dx) < PARALLEL) Double.POSITIVE_INFINITY else sizeX / abs(ray.dx)
      val deltaZ = if (abs(ray.dz) < PARALLEL) Double.POSITIVE_INFINITY else sizeZ / abs(ray.dz)
      var nextX = when {
        abs(ray.dx) < PARALLEL -> Double.POSITIVE_INFINITY
        ray.dx > 0 -> (minX + (cx + 1) * sizeX - ray.ox) / ray.dx
        else -> (minX + cx * sizeX - ray.ox) / ray.dx
      }
      var nextZ = when {
        abs(ray.dz) < PARALLEL -> Double.POSITIVE_INFINITY
        ray.dz > 0 -> (minZ + (cz + 1) * sizeZ - ray.oz) / ray.dz
        else -> (minZ + cz * sizeZ - ray.oz) / ray.dz
      }

      var best = -1.0
      while (true) {
        val exit = min(min(nextX, nextZ), t1)
        val c = cz * nx + cx
        for (i in cellStart[c] until cellStart[c + 1]) {
          val id = items[i]
          val s = surfaceOf(id)
          if (!visible[s]) continue
          val surface = surfaces[s]
          val tri = id - offsets[s]
          val ix = surface.indices
          val t = rayTriangle(ray, k, surface.positions, ix[tri * 3] * 3, ix[tri * 3 + 1] * 3, ix[tri * 3 + 2] * 3)
          if (t > 0.0 && (best < 0.0 || t < best)) best = t
        }
        if (best > 0.0 && best <= exit) return best
        if (exit >= t1) return best
        if (nextX < nextZ) {
          cx += stepX
          if (cx !in 0 until nx) return best
          nextX += deltaX
        } else {
          cz += stepZ
          if (cz !in 0 until nz) return best
          nextZ += deltaZ
        }
      }
    }

    // поверхностей единицы — линейного поиска хватает
    private fun surfaceOf(id: Int): Int {
      var s = 0
      while (id >= offsets[s + 1]) s++
      return s
    }

    companion object {
      fun of(surfaces: List<RenderSurface>, offsets: IntArray): Grid {
        var minX = Double.POSITIVE_INFINITY; var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxZ = Double.NEGATIVE_INFINITY
        for (surface in surfaces) {
          val p = surface.positions
          for (v in 0 until surface.vertexCount) {
            val x = p[v * 3].toDouble(); val z = p[v * 3 + 2].toDouble()
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
          }
        }
        val count = offsets[surfaces.size]
        val width = max(maxX - minX, MIN_SIZE)
        val depth = max(maxZ - minZ, MIN_SIZE)
        val cells = (count / TRIANGLES_PER_CELL).coerceIn(1, MAX_CELLS)
        val nx = sqrt(cells * width / depth).roundToInt().coerceIn(1, cells)
        val nz = (cells / nx).coerceAtLeast(1)
        val sizeX = width / nx
        val sizeZ = depth / nz
        val grid = Grid(surfaces, offsets, minX, minZ, minX + width, minZ + depth, nx, nz, sizeX, sizeZ, IntArray(nx * nz + 1), IntArray(0))

        // два прохода: сначала число треугольников на ячейку (по габаритам треугольника в плане), потом раскладка
        fun forEachCell(block: (cell: Int, id: Int) -> Unit) {
          for ((s, surface) in surfaces.withIndex()) {
            val p = surface.positions
            val ix = surface.indices
            for (tri in 0 until surface.triangleCount) {
              val a = ix[tri * 3] * 3; val b = ix[tri * 3 + 1] * 3; val c = ix[tri * 3 + 2] * 3
              val x0 = grid.cellX(min(p[a], min(p[b], p[c])).toDouble()); val x1 = grid.cellX(max(p[a], max(p[b], p[c])).toDouble())
              val z0 = grid.cellZ(min(p[a + 2], min(p[b + 2], p[c + 2])).toDouble()); val z1 = grid.cellZ(max(p[a + 2], max(p[b + 2], p[c + 2])).toDouble())
              for (z in z0..z1) for (x in x0..x1) block(z * nx + x, offsets[s] + tri)
            }
          }
        }
        val start = grid.cellStart
        forEachCell { cell, _ -> start[cell + 1]++ }
        for (i in 1..nx * nz) start[i] += start[i - 1]
        val items = IntArray(start[nx * nz])
        val fill = start.copyOf(nx * nz)
        forEachCell { cell, id -> items[fill[cell]++] = id }
        return Grid(surfaces, offsets, minX, minZ, minX + width, minZ + depth, nx, nz, sizeX, sizeZ, start, items)
      }
    }
  }

  private companion object {
    /** Без сетки перебираются поверхности до стольких треугольников: на слабом планшете это 5–6 мс. */
    const val BRUTE_FORCE_LIMIT = 50_000
    const val TRIANGLES_PER_CELL = 2
    const val MAX_CELLS = 1 shl 21
    const val MIN_SIZE = 1e-6
    const val PARALLEL = 1e-12
  }
}
