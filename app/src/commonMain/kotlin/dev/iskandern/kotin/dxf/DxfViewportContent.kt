package dev.iskandern.kotin.dxf

import dev.iskandern.kotin.model.FillBatch
import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.LayerInfo
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.NO_OBJECT
import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.RawMeshBuilder
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneLabel
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.model.toRadians
import kotlin.math.cos
import kotlin.math.sin

/**
 * Окно листа, в котором видна модель (вид сверху): прямоугольник на листе — центр [centerX], [centerY], размеры
 * [width] × [height]; вид — центр [viewCenterX], [viewCenterY] (в системе вида), цель [targetX], [targetY], высота
 * [viewHeight], поворот [twistDeg]; слои [frozenLayers] в окне заморожены.
 */
class SheetWindow(
  val centerX: Double, val centerY: Double, val width: Double, val height: Double,
  val viewCenterX: Double, val viewCenterY: Double, val targetX: Double, val targetY: Double,
  val viewHeight: Double, val twistDeg: Double, val frozenLayers: Set<String>,
) {
  constructor(viewport: DxfViewport) : this(
    viewport.centerX, viewport.centerY, viewport.width, viewport.height, viewport.viewCenterX, viewport.viewCenterY,
    viewport.targetX, viewport.targetY, viewport.viewHeight, viewport.twistDeg, viewport.frozenLayerNames,
  )
}

/**
 * Лист с моделью в окнах [windows]: уже раскрытая геометрия модели [model] переносится в каждое окно
 * преобразованием вида и обрезается по прямоугольнику окна. Модель не раскрывается заново — это проход по готовым
 * массивам. Отрезки режутся, заливки и сетки — по контуру, подписи и точки остаются целиком, если точка вставки
 * внутри окна. Слои, замороженные в окне, пропускаются. Содержимое окон рисуется под сущностями листа [sheet];
 * объекты модели добавляются в конец списка объектов листа.
 */
fun sheetWithViewports(sheet: SceneGeometry, model: SceneGeometry, windows: List<SheetWindow>, maxSegments: Int): SceneGeometry {
  val clips = windows.map { ViewportClip(it) }
  if (clips.isEmpty()) return sheet
  val objectOffset = sheet.objects.size
  fun objectId(id: Int) = if (id == NO_OBJECT) NO_OBJECT else id + objectOffset
  var budget = maxSegments - sheet.lines.sumOf { it.coords.size / 6 }
  // модель, обрезанная лимитом при раскрытии, и в окнах неполная
  var truncated = sheet.truncated || model.truncated

  // отрезки: пакет модели во всех окнах — один пакет листа
  val lines = ArrayList<LineBatch>()
  for (batch in model.lines) {
    val out = GrowingDoubleArray()
    val ids = if (batch.objectIds != null) GrowingIntArray() else null
    for (clip in clips) {
      if (batch.layer in clip.frozen) continue
      val c = batch.coords
      for (s in 0 until c.size / 6) {
        if (budget <= 0) {
          truncated = true
          break
        }
        val o = s * 6
        if (!clip.segment(c[o], c[o + 1], c[o + 2], c[o + 3], c[o + 4], c[o + 5], out)) continue
        budget--
        ids?.add(objectId(batch.objectIds!![s]))
      }
    }
    if (out.size == 0) continue
    lines.add(
      LineBatch(
        batch.layer, batch.color, batch.flat, out.toArray(), batch.lineWeight, batch.extrusion, unbounded = false,
        objectIds = ids?.toArray(), dashed = batch.dashed,
      ),
    )
  }

  // заливки: под заливками листа — номера порядка листа сдвигаются за последний номер модели
  val fills = ArrayList<FillBatch>()
  var maxOrder = -1
  for (batch in model.fills) {
    val out = GrowingDoubleArray()
    val colors = if (batch.colors != null) GrowingIntArray() else null
    val orders = GrowingIntArray()
    val ids = if (batch.objectIds != null) GrowingIntArray() else null
    for (clip in clips) {
      if (batch.layer in clip.frozen) continue
      for (t in 0 until batch.triangleCount) {
        val added = clip.triangle(batch.coords, t * 9, batch.colors, t * 3, out, colors)
        val order = batch.orders?.get(t) ?: 0
        for (k in 0 until added) {
          orders.add(order)
          ids?.add(objectId(batch.objectIds!![t]))
        }
        if (added > 0 && order > maxOrder) maxOrder = order
      }
    }
    if (out.size == 0) continue
    val colorArray = colors?.toArray()
    fills.add(
      FillBatch(
        batch.layer, colorArray?.first() ?: batch.color, batch.flat, out.toArray(), colorArray, batch.extrusion,
        orders.toArray(), batch.alpha, ids?.toArray(),
      ),
    )
  }
  val orderShift = maxOrder + 1
  for (batch in sheet.fills) {
    fills.add(
      if (orderShift == 0) batch else FillBatch(
        batch.layer, batch.color, batch.flat, batch.coords, batch.colors, batch.extrusion,
        IntArray(batch.triangleCount) { (batch.orders?.get(it) ?: 0) + orderShift }, batch.alpha, batch.objectIds,
      ),
    )
  }

  val meshes = ArrayList<SceneMesh>()
  for (mesh in model.meshes) {
    val builder = RawMeshBuilder()
    for (clip in clips) if (mesh.layer !in clip.frozen) clip.mesh(mesh, builder, ::objectId)
    if (builder.triangleCount == 0) continue
    val built = builder.build(mesh.layer, mesh.structure)
    meshes.add(
      SceneMesh(
        mesh.layer, built.coords, built.triangles, built.minX, built.minY, built.minZ, built.maxX, built.maxY, built.maxZ,
        built.edgeMask, built.triangleObjects, objectId(mesh.objectId), mesh.structure, mesh.outline,
      ),
    )
  }

  val labels = ArrayList<SceneLabel>()
  val points = ArrayList<PointMark>()
  for (clip in clips) {
    for (label in model.labels) clip.label(label, ::objectId)?.let { labels.add(it) }
    for (point in model.points) clip.point(point, ::objectId)?.let { points.add(it) }
  }

  val layers = LinkedHashMap<String, LayerInfo>()
  for (layer in sheet.layers) layers[layer.name] = layer
  for (layer in model.layers) if (layer.name !in layers) layers[layer.name] = layer

  return SceneGeometry(
    meshes = meshes + sheet.meshes,
    lines = lines + sheet.lines,
    fills = fills,
    labels = labels + sheet.labels,
    points = points + sheet.points,
    layers = layers.values.toList(),
    truncated = truncated,
    pointMode = sheet.pointMode,
    pointSize = sheet.pointSize,
    lineWeightDisplay = sheet.lineWeightDisplay,
    hiddenBodyFaces = sheet.hiddenBodyFaces,
    unreadBodies = sheet.unreadBodies,
    objects = sheet.objects + model.objects,
  )
}

/**
 * Преобразование вида окна (вид сверху): точка модели p → центр окна + k · (R(поворот) · (p − цель) − центр вида),
 * k — высота окна / высота вида; центр вида (12/22) — в системе вида, её начало — цель (17/27). Обрезка — по
 * прямоугольнику окна на листе.
 */
private class ViewportClip(viewport: SheetWindow) {
  private val k = viewport.height / viewport.viewHeight
  private val cos = cos(toRadians(viewport.twistDeg))
  private val sin = sin(toRadians(viewport.twistDeg))
  private val twist = viewport.twistDeg
  private val dx = viewport.centerX - k * viewport.viewCenterX
  private val dy = viewport.centerY - k * viewport.viewCenterY
  private val tx = viewport.targetX
  private val ty = viewport.targetY
  private val x0 = viewport.centerX - viewport.width / 2
  private val y0 = viewport.centerY - viewport.height / 2
  private val x1 = viewport.centerX + viewport.width / 2
  private val y1 = viewport.centerY + viewport.height / 2
  val frozen: Set<String> = viewport.frozenLayers

  private fun px(x: Double, y: Double) = dx + k * (cos * (x - tx) - sin * (y - ty))
  private fun py(x: Double, y: Double) = dy + k * (sin * (x - tx) + cos * (y - ty))
  private fun inside(x: Double, y: Double) = x in x0..x1 && y in y0..y1

  /** Отрезок модели, обрезанный по окну (Лян — Барски), дописывается в [out]; false — целиком снаружи. */
  fun segment(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, out: GrowingDoubleArray): Boolean {
    val x = px(ax, ay)
    val y = py(ax, ay)
    val xb = px(bx, by)
    val yb = py(bx, by)
    if (maxOf(x, xb) < x0 || minOf(x, xb) > x1 || maxOf(y, yb) < y0 || minOf(y, yb) > y1) return false
    val ex = xb - x
    val ey = yb - y
    var t0 = 0.0
    var t1 = 1.0
    // по сторонам окна: p · t ≤ q
    for (side in 0 until 4) {
      val p = when (side) { 0 -> -ex; 1 -> ex; 2 -> -ey; else -> ey }
      val q = when (side) { 0 -> x - x0; 1 -> x1 - x; 2 -> y - y0; else -> y1 - y }
      if (p == 0.0) {
        if (q < 0) return false
        continue
      }
      val r = q / p
      if (p < 0) {
        if (r > t1) return false
        if (r > t0) t0 = r
      } else {
        if (r < t0) return false
        if (r < t1) t1 = r
      }
    }
    val z = az * k
    val ez = (bz - az) * k
    out.add(x + ex * t0); out.add(y + ey * t0); out.add(z + ez * t0)
    out.add(x + ex * t1); out.add(y + ey * t1); out.add(z + ez * t1)
    return true
  }

  // многоугольник после обрезки треугольника: x, y, z, цвет и признак «ребро из исходного треугольника» у каждой вершины
  // (ребро от вершины i к i + 1); треугольник при обрезке прямоугольником даёт не больше 7 вершин
  private var polyX = DoubleArray(MAX_POLY)
  private var polyY = DoubleArray(MAX_POLY)
  private var polyZ = DoubleArray(MAX_POLY)
  private var polyC = IntArray(MAX_POLY)
  private var polyE = BooleanArray(MAX_POLY)
  private var size = 0
  private var nextX = DoubleArray(MAX_POLY)
  private var nextY = DoubleArray(MAX_POLY)
  private var nextZ = DoubleArray(MAX_POLY)
  private var nextC = IntArray(MAX_POLY)
  private var nextE = BooleanArray(MAX_POLY)

  /**
   * Треугольник модели (цвета вершин [ca]…[cc], видимые рёбра [mask]) в многоугольник окна;
   * 0 — целиком снаружи, 1 — внутри целиком (многоугольник — сам треугольник), 2 — обрезан.
   */
  private fun clipTriangle(
    ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, cx: Double, cy: Double, cz: Double,
    ca: Int, cb: Int, cc: Int, mask: Int = ALL_EDGES,
  ): Int {
    polyX[0] = px(ax, ay); polyY[0] = py(ax, ay); polyZ[0] = az * k; polyC[0] = ca
    polyX[1] = px(bx, by); polyY[1] = py(bx, by); polyZ[1] = bz * k; polyC[1] = cb
    polyX[2] = px(cx, cy); polyY[2] = py(cx, cy); polyZ[2] = cz * k; polyC[2] = cc
    polyE[0] = mask and EDGE_AB != 0; polyE[1] = mask and EDGE_BC != 0; polyE[2] = mask and EDGE_CA != 0
    size = 3
    val minX = minOf(polyX[0], polyX[1], polyX[2])
    val maxX = maxOf(polyX[0], polyX[1], polyX[2])
    val minY = minOf(polyY[0], polyY[1], polyY[2])
    val maxY = maxOf(polyY[0], polyY[1], polyY[2])
    if (maxX < x0 || minX > x1 || maxY < y0 || minY > y1) return 0
    if (minX >= x0 && maxX <= x1 && minY >= y0 && maxY <= y1) return 1
    for (side in 0 until 4) {
      clipSide(side)
      if (size < 3) return 0
    }
    return 2
  }

  private fun distance(side: Int, i: Int) = when (side) {
    0 -> polyX[i] - x0
    1 -> x1 - polyX[i]
    2 -> polyY[i] - y0
    else -> y1 - polyY[i]
  }

  // Сазерленд — Ходжмен по одной стороне окна: 0 — левая, 1 — правая, 2 — нижняя, 3 — верхняя
  private fun clipSide(side: Int) {
    var n = 0
    for (i in 0 until size) {
      val j = if (i + 1 == size) 0 else i + 1
      val di = distance(side, i)
      val dj = distance(side, j)
      if (di >= 0) {
        nextX[n] = polyX[i]; nextY[n] = polyY[i]; nextZ[n] = polyZ[i]; nextC[n] = polyC[i]; nextE[n] = polyE[i]
        n++
      }
      if ((di >= 0) != (dj >= 0)) {
        val t = di / (di - dj)
        nextX[n] = polyX[i] + (polyX[j] - polyX[i]) * t
        nextY[n] = polyY[i] + (polyY[j] - polyY[i]) * t
        nextZ[n] = polyZ[i] + (polyZ[j] - polyZ[i]) * t
        nextC[n] = mixColor(polyC[i], polyC[j], t)
        // точка входа продолжает исходное ребро, от точки выхода ребро идёт по стороне окна
        nextE[n] = polyE[i] && di < 0
        n++
      }
    }
    var swap = polyX; polyX = nextX; nextX = swap
    swap = polyY; polyY = nextY; nextY = swap
    swap = polyZ; polyZ = nextZ; nextZ = swap
    val swapC = polyC; polyC = nextC; nextC = swapC
    val swapE = polyE; polyE = nextE; nextE = swapE
    size = n
  }

  /** Треугольник заливки: части в окне дописываются в [out] (и цвета в [outColors]); возвращает число треугольников. */
  fun triangle(c: DoubleArray, o: Int, colors: IntArray?, co: Int, out: GrowingDoubleArray, outColors: GrowingIntArray?): Int {
    val ca = colors?.get(co) ?: 0
    val cb = colors?.get(co + 1) ?: 0
    val cc = colors?.get(co + 2) ?: 0
    if (clipTriangle(c[o], c[o + 1], c[o + 2], c[o + 3], c[o + 4], c[o + 5], c[o + 6], c[o + 7], c[o + 8], ca, cb, cc) == 0) return 0
    for (i in 1 until size - 1) {
      addVertex(0, out, outColors)
      addVertex(i, out, outColors)
      addVertex(i + 1, out, outColors)
    }
    return size - 2
  }

  private fun addVertex(v: Int, out: GrowingDoubleArray, outColors: GrowingIntArray?) {
    out.add(polyX[v]); out.add(polyY[v]); out.add(polyZ[v])
    outColors?.add(polyC[v])
  }

  /** Треугольники сетки в окне — в [builder]; целые треугольники делят вершины, обрезанные — со своими. */
  fun mesh(mesh: SceneMesh, builder: RawMeshBuilder, objectId: (Int) -> Int) {
    val v = mesh.coords
    val remap = IntArray(mesh.vertexCount) { -1 }
    val tri = mesh.triangles
    for (t in 0 until mesh.triangleCount) {
      val a = tri[t * 3]
      val b = tri[t * 3 + 1]
      val c = tri[t * 3 + 2]
      val mask = mesh.edgeMask?.get(t)?.toInt() ?: ALL_EDGES
      val result = clipTriangle(
        v[a * 3], v[a * 3 + 1], v[a * 3 + 2], v[b * 3], v[b * 3 + 1], v[b * 3 + 2], v[c * 3], v[c * 3 + 1], v[c * 3 + 2],
        0, 0, 0, mask,
      )
      if (result == 0) continue
      builder.markObject(mesh.triangleObjects?.get(t)?.let(objectId) ?: NO_OBJECT)
      if (result == 1) {
        builder.triangle(meshVertex(a, v, remap, builder), meshVertex(b, v, remap, builder), meshVertex(c, v, remap, builder), mask)
        continue
      }
      // обрезанный: веер от первой вершины; видны только части исходных видимых рёбер, не стороны окна
      val first = builder.vertexCount
      for (i in 0 until size) builder.vertex(polyX[i], polyY[i], polyZ[i])
      for (i in 1 until size - 1) {
        var edges = 0
        if (i == 1 && polyE[0]) edges = edges or EDGE_AB
        if (polyE[i]) edges = edges or EDGE_BC
        if (i == size - 2 && polyE[size - 1]) edges = edges or EDGE_CA
        builder.triangle(first, first + i, first + i + 1, edges)
      }
    }
  }

  // вершина сетки модели в окне — одна на все целые треугольники
  private fun meshVertex(i: Int, v: DoubleArray, remap: IntArray, builder: RawMeshBuilder): Int {
    if (remap[i] < 0) {
      remap[i] = builder.vertexCount
      builder.vertex(px(v[i * 3], v[i * 3 + 1]), py(v[i * 3], v[i * 3 + 1]), v[i * 3 + 2] * k)
    }
    return remap[i]
  }

  fun label(label: SceneLabel, objectId: (Int) -> Int): SceneLabel? {
    if (label.layer in frozen) return null
    val x = px(label.x, label.y)
    val y = py(label.x, label.y)
    if (!inside(x, y)) return null
    return SceneLabel(
      label.layer, label.color, x, y, label.z * k, label.height * k, label.rotationDeg + twist, label.text,
      label.alignH, label.alignV, label.widthFactor, label.obliqueDeg, label.fitLength * k,
      label.frame?.scaled(k, k), label.runs,
      label.background?.let { DxfTextBackground(it.color, it.margin, it.width * k, it.edgeColor, it.edge) },
      label.font, objectId(label.objectId), label.screenHorizontal, label.liftDp, label.shiftDp, label.tickRgb,
    )
  }

  fun point(point: PointMark, objectId: (Int) -> Int): PointMark? {
    if (point.layer in frozen) return null
    val x = px(point.x, point.y)
    val y = py(point.x, point.y)
    if (!inside(x, y)) return null
    return PointMark(point.layer, point.color, x, y, point.z * k, point.rotationDeg + twist, objectId(point.objectId))
  }

  private companion object {
    const val MAX_POLY = 8
    const val ALL_EDGES = 7
    const val EDGE_AB = 1
    const val EDGE_BC = 2
    const val EDGE_CA = 4
  }
}

// цвет 0xRRGGBB между [a] и [b] по доле [t]
private fun mixColor(a: Int, b: Int, t: Double): Int {
  if (a == b) return a
  fun channel(shift: Int): Int {
    val ca = (a shr shift) and 0xFF
    val cb = (b shr shift) and 0xFF
    return (ca + (cb - ca) * t + 0.5).toInt().coerceIn(0, 255) shl shift
  }
  return channel(16) or channel(8) or channel(0)
}
