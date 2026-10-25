package dev.iskandern.kotin.scene

import dev.iskandern.kotin.model.NO_OBJECT
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Контур чертежа в плане: [hull] — вершины против часовой стрелки парами (x, z) сцены, [minY]..[maxY] — разброс по
 * высоте без вертикального масштаба. По нему указатель решает, ушёл ли чертёж из кадра: габариты сцены симметричны
 * вокруг центра и у вытянутой по диагонали или смещённой геометрии гораздо больше её самой.
 * [samples] — опорные точки самой геометрии (тройки x, y, z): не больше одной на клетку сетки [SAMPLE_GRID] ×
 * [SAMPLE_GRID] по контуру; по ним нажатие на указатель ищет ближайшую часть чертежа без перебора всех вершин.
 */
class DrawingOutline(val hull: FloatArray, val minY: Float, val maxY: Float, val samples: FloatArray = FloatArray(0)) {
  val size: Int get() = hull.size / 2

  /**
   * Ближайшая в плане к ([x], [z]) опорная точка геометрии (x, y, z; высота без вертикального масштаба) — точнее
   * клетки сетки, но всегда на самом чертеже; null — опорных точек нет. Не больше [SAMPLE_GRID]² точек на нажатие.
   */
  fun nearest(x: Float, z: Float): FloatArray? {
    var best = -1
    var bestDist = Float.POSITIVE_INFINITY
    for (i in 0 until samples.size / 3) {
      val dx = samples[i * 3] - x
      val dz = samples[i * 3 + 2] - z
      val d = dx * dx + dz * dz
      if (d < bestDist) {
        bestDist = d
        best = i
      }
    }
    return if (best < 0) null else samples.copyOfRange(best * 3, best * 3 + 3)
  }
}

/**
 * Контур по той же группе, что и центр сцены: поверхности → линии, заливки и точки → подписи. Далёкая подпись в
 * контур не входит, как и в кадр исходного вида. null — геометрии нет. Два прохода по вершинам группы: опорные
 * прямые контура, затем опорные точки по сетке внутри него (у отрезков — и точки вдоль них, а не только концы).
 */
internal fun drawingOutline(
  surfaces: List<RenderSurface>, lines: List<RenderLines>, fills: List<RenderFills>, points: List<RenderPoint>, labels: List<RenderLabel>,
): DrawingOutline? {
  val hasSurfaces = surfaces.any { it.positions.size >= 3 }
  val hasDrawing = !hasSurfaces && (lines.any { it.positions.size >= 3 } || fills.any { it.positions.size >= 3 } || points.isNotEmpty())
  fun visit(vertex: (Float, Float, Float) -> Unit, segment: (FloatArray, Int, Int) -> Unit) {
    when {
      hasSurfaces -> surfaces.forEach { forEachVertex(it.positions, vertex) }
      hasDrawing -> {
        lines.forEach { batch -> for (s in 0 until batch.indices.size / 2) segment(batch.positions, batch.indices[s * 2], batch.indices[s * 2 + 1]) }
        fills.forEach { forEachVertex(it.positions, vertex) }
        points.forEach { vertex(it.x, it.y, it.z) }
      }
      else -> labels.forEach { vertex(it.x, it.y, it.z) }
    }
  }
  val support = PlanSupport()
  visit(support::add) { p, a, b ->
    support.add(p[a * 3], p[a * 3 + 1], p[a * 3 + 2])
    support.add(p[b * 3], p[b * 3 + 1], p[b * 3 + 2])
  }
  val outline = support.outline() ?: return null
  val grid = SampleGrid(outline.hull)
  visit(grid::add, grid::segment)
  return DrawingOutline(outline.hull, outline.minY, outline.maxY, grid.samples())
}

/** К какой части чертежа относится примитив: по объекту [id] или середине его габаритов в плане ([cx], [cz]); −1 — ни к какой. */
internal fun interface PrimitiveSlot {
  fun slot(id: Int, cx: Float, cz: Float): Int
}

/**
 * Контуры частей чертежа 0 until [count] (группа та же, что у [drawingOutline]): поверхность [i] — в части
 * [surfaceSlots] (−1 — ни в какой), остальные примитивы раскладывает [slot]. Те же два прохода, что у [drawingOutline];
 * null — у части нет геометрии.
 */
internal fun drawingOutlines(
  surfaces: List<RenderSurface>, lines: List<RenderLines>, fills: List<RenderFills>, points: List<RenderPoint>, labels: List<RenderLabel>,
  count: Int, surfaceSlots: IntArray, slot: PrimitiveSlot,
): Array<DrawingOutline?> {
  val supports = Array(count) { PlanSupport() }
  forEachSlotted(surfaces, lines, fills, points, labels, surfaceSlots, slot, { k, x, y, z -> supports[k].add(x, y, z) }) { k, p, a, b ->
    supports[k].add(p[a * 3], p[a * 3 + 1], p[a * 3 + 2])
    supports[k].add(p[b * 3], p[b * 3 + 1], p[b * 3 + 2])
  }
  val hulls = Array(count) { supports[it].outline() }
  val grids = Array(count) { k -> hulls[k]?.let { SampleGrid(it.hull) } }
  forEachSlotted(surfaces, lines, fills, points, labels, surfaceSlots, slot, { k, x, y, z -> grids[k]?.add(x, y, z) }) { k, p, a, b ->
    grids[k]?.segment(p, a, b)
  }
  return Array(count) { k ->
    val hull = hulls[k] ?: return@Array null
    DrawingOutline(hull.hull, hull.minY, hull.maxY, grids[k]!!.samples())
  }
}

// вершины и отрезки группы с номером части; середина габаритов примитива — как у кадра исходного вида (HomeFrame.kt)
private inline fun forEachSlotted(
  surfaces: List<RenderSurface>, lines: List<RenderLines>, fills: List<RenderFills>, points: List<RenderPoint>, labels: List<RenderLabel>,
  surfaceSlots: IntArray, slot: PrimitiveSlot, vertex: (Int, Float, Float, Float) -> Unit, segment: (Int, FloatArray, Int, Int) -> Unit,
) {
  val hasSurfaces = surfaces.any { it.positions.size >= 3 }
  val hasDrawing = !hasSurfaces && (lines.any { it.positions.size >= 3 } || fills.any { it.positions.size >= 3 } || points.isNotEmpty())
  when {
    hasSurfaces -> surfaces.forEachIndexed { i, s ->
      val k = surfaceSlots[i]
      if (k >= 0) forEachVertex(s.positions) { x, y, z -> vertex(k, x, y, z) }
    }
    hasDrawing -> {
      for (batch in lines) {
        val p = batch.positions
        val ids = batch.objectIds
        for (s in 0 until batch.indices.size / 2) {
          val a = batch.indices[s * 2]
          val b = batch.indices[s * 2 + 1]
          val k = slot.slot(ids?.get(s) ?: NO_OBJECT, (p[a * 3] + p[b * 3]) / 2, (p[a * 3 + 2] + p[b * 3 + 2]) / 2)
          if (k >= 0) segment(k, p, a, b)
        }
      }
      for (batch in fills) {
        val p = batch.positions
        val ids = batch.objectIds
        for (t in 0 until batch.indices.size / 3) {
          val a = batch.indices[t * 3] * 3
          val b = batch.indices[t * 3 + 1] * 3
          val c = batch.indices[t * 3 + 2] * 3
          val cx = (minOf(p[a], p[b], p[c]) + maxOf(p[a], p[b], p[c])) / 2
          val cz = (minOf(p[a + 2], p[b + 2], p[c + 2]) + maxOf(p[a + 2], p[b + 2], p[c + 2])) / 2
          val k = slot.slot(ids?.get(t) ?: NO_OBJECT, cx, cz)
          if (k < 0) continue
          vertex(k, p[a], p[a + 1], p[a + 2])
          vertex(k, p[b], p[b + 1], p[b + 2])
          vertex(k, p[c], p[c + 1], p[c + 2])
        }
      }
      for (point in points) {
        val k = slot.slot(point.objectId, point.x, point.z)
        if (k >= 0) vertex(k, point.x, point.y, point.z)
      }
    }
    else -> for (label in labels) {
      val k = slot.slot(label.objectId, label.x, label.z)
      if (k >= 0) vertex(k, label.x, label.y, label.z)
    }
  }
}

private inline fun forEachVertex(positions: FloatArray, vertex: (Float, Float, Float) -> Unit) {
  for (i in 0 until positions.size / 3) vertex(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
}

/** Контур по вершинам [positions] (тройки x, y, z сцены); null — вершин нет. */
internal fun drawingOutlineOf(positions: FloatArray): DrawingOutline? {
  val outline = PlanSupport().apply { addAll(positions) }.outline() ?: return null
  val grid = SampleGrid(outline.hull)
  forEachVertex(positions, grid::add)
  return DrawingOutline(outline.hull, outline.minY, outline.maxY, grid.samples())
}

/** Первая попавшая в клетку точка геометрии — на сетке [SAMPLE_GRID] × [SAMPLE_GRID] по прямоугольнику контура. */
private class SampleGrid(hull: FloatArray) {
  private val minX: Float
  private val minZ: Float
  private val cellX: Float
  private val cellZ: Float
  private val step: Float
  private val filled = BooleanArray(SAMPLE_GRID * SAMPLE_GRID)
  private val points = FloatArray(SAMPLE_GRID * SAMPLE_GRID * 3)
  private var count = 0

  init {
    var x0 = Float.POSITIVE_INFINITY; var x1 = Float.NEGATIVE_INFINITY
    var z0 = Float.POSITIVE_INFINITY; var z1 = Float.NEGATIVE_INFINITY
    for (i in 0 until hull.size / 2) {
      x0 = minOf(x0, hull[i * 2]); x1 = maxOf(x1, hull[i * 2])
      z0 = minOf(z0, hull[i * 2 + 1]); z1 = maxOf(z1, hull[i * 2 + 1])
    }
    minX = x0; minZ = z0
    // у вырожденного (точка, линия по оси) контура клетка по пустой оси — по другой
    val size = maxOf(x1 - x0, z1 - z0, 1e-6f)
    cellX = maxOf(x1 - x0, size * 1e-3f) / SAMPLE_GRID
    cellZ = maxOf(z1 - z0, size * 1e-3f) / SAMPLE_GRID
    step = minOf(cellX, cellZ) / 2f
  }

  fun add(x: Float, y: Float, z: Float) {
    if (count == filled.size || !x.isFinite() || !y.isFinite() || !z.isFinite()) return
    val cx = ((x - minX) / cellX).toInt().coerceIn(0, SAMPLE_GRID - 1)
    val cz = ((z - minZ) / cellZ).toInt().coerceIn(0, SAMPLE_GRID - 1)
    val cell = cz * SAMPLE_GRID + cx
    if (filled[cell]) return
    filled[cell] = true
    points[count * 3] = x; points[count * 3 + 1] = y; points[count * 3 + 2] = z
    count++
  }

  // отрезок — точки вдоль него с шагом в полклетки: длинная линия попадает во все клетки, через которые проходит
  fun segment(p: FloatArray, a: Int, b: Int) {
    val ax = p[a * 3]; val ay = p[a * 3 + 1]; val az = p[a * 3 + 2]
    val ex = p[b * 3] - ax; val ey = p[b * 3 + 1] - ay; val ez = p[b * 3 + 2] - az
    val length = kotlin.math.sqrt(ex * ex + ez * ez)
    val n = if (length.isFinite()) (length / step).toInt().coerceIn(0, MAX_SEGMENT_SAMPLES) else 0
    for (i in 0..n) {
      val t = if (n == 0) 0f else i.toFloat() / n
      add(ax + ex * t, ay + ey * t, az + ez * t)
    }
  }

  fun samples(): FloatArray = points.copyOf(count * 3)
}

/** Сторона сетки опорных точек: до 4096 точек на чертёж. */
private const val SAMPLE_GRID = 64

/** Точек вдоль одного отрезка не больше: он и через всю сетку проходит меньше чем за ~4 · [SAMPLE_GRID] шагов. */
private const val MAX_SEGMENT_SAMPLES = SAMPLE_GRID * 4

/**
 * Описанный многоугольник за один проход без выделений: по каждому из [OUTLINE_DIRECTIONS] направлений — дальняя
 * точка (опорная прямая), вершины контура — пересечения соседних опорных прямых. Шире выпуклой оболочки не больше
 * чем в 1 / cos(π / [OUTLINE_DIRECTIONS]) раз (~2 %): указатель может появиться чуть позже, но не при чертеже в кадре.
 */
private class PlanSupport {
  private val reach = FloatArray(OUTLINE_DIRECTIONS) { Float.NEGATIVE_INFINITY }
  var minY = Float.POSITIVE_INFINITY
    private set
  var maxY = Float.NEGATIVE_INFINITY
    private set
  val isEmpty: Boolean get() = minY > maxY

  fun addAll(positions: FloatArray) {
    for (i in 0 until positions.size / 3) add(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2])
  }

  fun add(x: Float, y: Float, z: Float) {
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
    for (k in 0 until OUTLINE_DIRECTIONS) {
      val d = x * DIR_COS[k] + z * DIR_SIN[k]
      if (d > reach[k]) reach[k] = d
    }
    if (y < minY) minY = y
    if (y > maxY) maxY = y
  }

  fun outline(): DrawingOutline? {
    if (isEmpty) return null
    // пересечение опорных прямых k и k + 1: x·cos + z·sin = reach, определитель — sin угла между ними
    val det = sin(2 * PI / OUTLINE_DIRECTIONS).toFloat()
    val hull = FloatArray(OUTLINE_DIRECTIONS * 2)
    for (k in 0 until OUTLINE_DIRECTIONS) {
      val j = (k + 1) % OUTLINE_DIRECTIONS
      hull[k * 2] = (reach[k] * DIR_SIN[j] - reach[j] * DIR_SIN[k]) / det
      hull[k * 2 + 1] = (DIR_COS[k] * reach[j] - DIR_COS[j] * reach[k]) / det
    }
    return DrawingOutline(hull, minY, maxY)
  }
}

/** Число опорных направлений контура. */
private const val OUTLINE_DIRECTIONS = 16
private val DIR_COS = FloatArray(OUTLINE_DIRECTIONS) { cos(2 * PI * it / OUTLINE_DIRECTIONS).toFloat() }
private val DIR_SIN = FloatArray(OUTLINE_DIRECTIONS) { sin(2 * PI * it / OUTLINE_DIRECTIONS).toFloat() }

