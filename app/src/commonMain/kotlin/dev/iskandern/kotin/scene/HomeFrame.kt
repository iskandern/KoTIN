package dev.iskandern.kotin.scene

import dev.iskandern.kotin.model.NO_OBJECT
import kotlin.math.max

/**
 * Кадр исходного вида — основная масса чертежа без далёких выбросов: центр ([x], [y], [z]) и полуразмеры в координатах
 * сцены. Центр и размер самой сцены ([RenderScene.halfX] и т. д.) не меняются — только куда смотрит камера.
 * [outline] — контур основной массы: по нему указатель «Чертёж» решает, ушёл ли чертёж из кадра; [outliers] — группы
 * выбросов, на каждую — свой указатель.
 */
class HomeFrame(
  val x: Float, val y: Float, val z: Float, val halfX: Float, val halfY: Float, val halfZ: Float,
  val outline: DrawingOutline?,
  val outliers: List<OutlierGroup>,
)

/** Далёкие объекты рядом друг с другом: контур в плане и сколько их (объект DXF или примитив без объекта — один). */
class OutlierGroup(val outline: DrawingOutline, val count: Int)

/**
 * Кадр без выбросов по той же группе, что и центр сцены (поверхности → линии, заливки и точки → подписи); null —
 * выбросов нет, кадр по всей сцене. Элемент — объект (габариты всех его примитивов), примитив без объекта — сам по
 * себе, поверхность — целиком. Центры элементов раскладываются по гистограммам вдоль X и Z (от −[halfX] до [halfX]
 * и т. д. — габариты сцены); окно — без [OUTLIER_SHARE] крайних элементов с каждой стороны (хотя бы одного),
 * раздвинутое на [WINDOW_MARGIN] своей ширины. Кадр — габариты элементов с центром в окне и частей остальных внутри
 * окна; выбросы отбрасываются, только если без них площадь в плане меньше всей хотя бы в [MIN_AREA_RATIO] раз:
 * обычный чертёж открывается как прежде.
 *
 * Сначала то же по выборке не больше [SAMPLE_LIMIT] примитивов без объектов ([likelyOutliers]): у обычного чертежа
 * на этом всё и кончается. Подозрение есть — два прохода по всей группе и ещё два на контуры частей.
 */
internal fun homeFrame(
  surfaces: List<RenderSurface>, lines: List<RenderLines>, fills: List<RenderFills>, points: List<RenderPoint>, labels: List<RenderLabel>,
  objectCount: Int, halfX: Float, halfZ: Float,
): HomeFrame? {
  val hasSurfaces = surfaces.any { it.positions.size >= 3 }
  val hasDrawing = !hasSurfaces && (lines.any { it.positions.size >= 3 } || fills.any { it.positions.size >= 3 } || points.isNotEmpty())
  if (hasSurfaces) {
    if (surfaces.size < MIN_ELEMENTS) return null
  } else if (!likelyOutliers(lines, fills, points, labels, hasDrawing, halfX, halfZ)) {
    return null
  }
  // габариты объектов: по шесть чисел (min x, y, z, max x, y, z); поверхность — элемент целиком
  val slots = if (hasSurfaces) 0 else objectCount
  val objects = FloatArray(slots * 6) { if (it % 6 < 3) Float.POSITIVE_INFINITY else Float.NEGATIVE_INFINITY }
  // середины поверхностей в плане (x, z)
  val surfaceCenters = FloatArray(surfaces.size * 2) { Float.NaN }
  val box = Box()

  // проход 1: габариты всего, объекты, гистограммы центров в плане
  val all = Box()
  val histX = Histogram(-halfX, halfX)
  val histZ = Histogram(-halfZ, halfZ)
  var count = 0
  fun gather(b: Box) {
    all.add(b)
    histX.add(b.centerX)
    histZ.add(b.centerZ)
    count++
  }
  if (hasSurfaces) {
    surfaces.forEachIndexed { i, s ->
      box.clear()
      box.addAll(s.positions)
      if (box.isEmpty) return@forEachIndexed
      surfaceCenters[i * 2] = box.centerX
      surfaceCenters[i * 2 + 1] = box.centerZ
      gather(box)
    }
  } else {
    forEachPrimitive(lines, fills, points, labels, hasDrawing, 1, box) { id ->
      if (id < 0 || id >= slots) gather(box) else box.into(objects, id * 6)
    }
  }
  for (i in 0 until slots) {
    if (objects[i * 6] > objects[i * 6 + 3]) continue
    box.set(objects, i * 6)
    gather(box)
  }
  if (count < MIN_ELEMENTS) return null
  val skip = max(1, (count * OUTLIER_SHARE).toInt())
  val (x0, x1) = histX.window(skip)
  val (z0, z1) = histZ.window(skip)

  // проход 2: кадр и габариты выбросов — по четыре числа (min x, max x, min z, max z)
  val core = Box()
  val excluded = FloatList()
  fun split(b: Box) {
    if (!core.addInWindow(b, x0, x1, z0, z1)) {
      excluded.add(b.minX); excluded.add(b.maxX); excluded.add(b.minZ); excluded.add(b.maxZ)
    }
  }
  if (hasSurfaces) {
    for (s in surfaces) {
      box.clear()
      box.addAll(s.positions)
      if (!box.isEmpty) split(box)
    }
  } else {
    forEachPrimitive(lines, fills, points, labels, hasDrawing, 1, box) { id -> if (id < 0 || id >= slots) split(box) }
  }
  for (i in 0 until slots) {
    if (objects[i * 6] > objects[i * 6 + 3]) continue
    box.set(objects, i * 6)
    split(box)
  }
  if (core.isEmpty || excluded.size == 0) return null
  val floor = sideFloor(all.maxX - all.minX, all.maxZ - all.minZ)
  if (planArea(core, floor) * MIN_AREA_RATIO > planArea(all, floor)) return null

  // выбросы ближе размера основной массы друг к другу — одна группа
  val groups = outlierGroups(excluded, max(max(core.maxX - core.minX, core.maxZ - core.minZ), floor))
  val groupCount = groups.size / GROUP_STRIDE
  fun slotOf(cx: Float, cz: Float): Int {
    if (cx in x0..x1 && cz in z0..z1) return 0
    for (g in 0 until groupCount) {
      val o = g * GROUP_STRIDE
      if (cx >= groups[o] && cx <= groups[o + 1] && cz >= groups[o + 2] && cz <= groups[o + 3]) return g + 1
    }
    return -1
  }
  val objectSlots = IntArray(slots) { i ->
    if (objects[i * 6] > objects[i * 6 + 3]) -1 else slotOf((objects[i * 6] + objects[i * 6 + 3]) / 2, (objects[i * 6 + 2] + objects[i * 6 + 5]) / 2)
  }
  val surfaceSlots = IntArray(surfaces.size) { slotOf(surfaceCenters[it * 2], surfaceCenters[it * 2 + 1]) }
  val outlines = drawingOutlines(surfaces, lines, fills, points, labels, 1 + groupCount, surfaceSlots) { id, cx, cz ->
    if (id in 0 until slots) objectSlots[id] else slotOf(cx, cz)
  }
  val outliers = (0 until groupCount).mapNotNull { g ->
    outlines[g + 1]?.let { OutlierGroup(it, groups[g * GROUP_STRIDE + 4].toInt()) }
  }
  return HomeFrame(
    core.centerX, (core.minY + core.maxY) / 2, core.centerZ,
    (core.maxX - core.minX) / 2, (core.maxY - core.minY) / 2, (core.maxZ - core.minZ) / 2,
    outlines[0], outliers,
  )
}

/**
 * Есть ли смысл искать выбросы: тот же расчёт кадра по выборке — каждый n-й примитив, не больше [SAMPLE_LIMIT], без
 * объектов — против габаритов сцены ±[halfX] × ±[halfZ]. Далёкий примитив может и не попасть в выборку: габариты
 * сцены его всё равно учитывают.
 */
private fun likelyOutliers(
  lines: List<RenderLines>, fills: List<RenderFills>, points: List<RenderPoint>, labels: List<RenderLabel>, drawing: Boolean,
  halfX: Float, halfZ: Float,
): Boolean {
  val total = if (drawing) lines.sumOf { it.indices.size / 2 } + fills.sumOf { it.indices.size / 3 } + points.size else labels.size
  if (total < MIN_ELEMENTS) return false
  val stride = max(1, total / SAMPLE_LIMIT)
  val box = Box()
  val histX = Histogram(-halfX, halfX)
  val histZ = Histogram(-halfZ, halfZ)
  var count = 0
  forEachPrimitive(lines, fills, points, labels, drawing, stride, box) {
    histX.add(box.centerX)
    histZ.add(box.centerZ)
    count++
  }
  if (count < MIN_ELEMENTS) return false
  val skip = max(1, (count * OUTLIER_SHARE).toInt())
  val (x0, x1) = histX.window(skip)
  val (z0, z1) = histZ.window(skip)
  val core = Box()
  forEachPrimitive(lines, fills, points, labels, drawing, stride, box) { core.addInWindow(box, x0, x1, z0, z1) }
  if (core.isEmpty) return false
  val floor = sideFloor(2 * halfX, 2 * halfZ)
  return planArea(core, floor) * MIN_AREA_RATIO <= max(2 * halfX, floor) * max(2 * halfZ, floor)
}

/**
 * Каждый [stride]-й примитив группы — отрезки, треугольники заливок и точки чертежа ([drawing]) или подписи: его
 * габариты в [box], затем [action] с номером объекта. Примитивы без конечных координат пропускаются.
 */
private inline fun forEachPrimitive(
  lines: List<RenderLines>, fills: List<RenderFills>, points: List<RenderPoint>, labels: List<RenderLabel>, drawing: Boolean,
  stride: Int, box: Box, action: (id: Int) -> Unit,
) {
  // шаг выборки идёт сквозь пакеты: from — первый номер в следующем
  var from = 0
  if (!drawing) {
    var i = 0
    while (i < labels.size) {
      val label = labels[i]
      box.clear()
      box.add(label.x, label.y, label.z)
      if (!box.isEmpty) action(label.objectId)
      i += stride
    }
    return
  }
  for (batch in lines) {
    val p = batch.positions
    val indices = batch.indices
    val ids = batch.objectIds
    val n = indices.size / 2
    var s = from
    while (s < n) {
      box.clear()
      box.add(p, indices[s * 2])
      box.add(p, indices[s * 2 + 1])
      if (!box.isEmpty) action(ids?.get(s) ?: NO_OBJECT)
      s += stride
    }
    from = s - n
  }
  for (batch in fills) {
    val p = batch.positions
    val indices = batch.indices
    val ids = batch.objectIds
    val n = indices.size / 3
    var t = from
    while (t < n) {
      box.clear()
      box.add(p, indices[t * 3])
      box.add(p, indices[t * 3 + 1])
      box.add(p, indices[t * 3 + 2])
      if (!box.isEmpty) action(ids?.get(t) ?: NO_OBJECT)
      t += stride
    }
    from = t - n
  }
  var i = from
  while (i < points.size) {
    val point = points[i]
    box.clear()
    box.add(point.x, point.y, point.z)
    if (!box.isEmpty) action(point.objectId)
    i += stride
  }
}

// у вытянутого в линию чертежа сторона для площади не меньше доли его размера
private fun sideFloor(sizeX: Float, sizeZ: Float) = max(max(sizeX, sizeZ) * MIN_SIDE_SHARE, 1e-6f)

private fun planArea(b: Box, floor: Float) = max(b.maxX - b.minX, floor) * max(b.maxZ - b.minZ, floor)

/**
 * Группы выбросов [excluded] (по четыре числа на элемент: min x, max x, min z, max z): элемент ближе [gap] к группе
 * входит в неё, группы ближе [gap] друг к другу сливаются, больше [MAX_GROUPS] — сливаются ближайшие. На группу
 * [GROUP_STRIDE] чисел: габариты и число элементов.
 */
private fun outlierGroups(excluded: FloatList, gap: Float): FloatArray {
  val groups = FloatList()
  fun distance(a: FloatArray, i: Int, b: FloatArray, j: Int): Float {
    val dx = max(max(a[i] - b[j + 1], b[j] - a[i + 1]), 0f)
    val dz = max(max(a[i + 2] - b[j + 3], b[j + 2] - a[i + 3]), 0f)
    return max(dx, dz)
  }
  fun merge(into: Int, from: FloatArray, j: Int, count: Float) {
    val g = groups.items
    g[into] = minOf(g[into], from[j]); g[into + 1] = maxOf(g[into + 1], from[j + 1])
    g[into + 2] = minOf(g[into + 2], from[j + 2]); g[into + 3] = maxOf(g[into + 3], from[j + 3])
    g[into + 4] += count
  }
  val e = excluded.items
  for (i in 0 until excluded.size / 4) {
    var best = -1
    var bestDistance = Float.POSITIVE_INFINITY
    for (g in 0 until groups.size / GROUP_STRIDE) {
      val d = distance(groups.items, g * GROUP_STRIDE, e, i * 4)
      if (d < bestDistance) { bestDistance = d; best = g }
    }
    // групп уже много — к ближайшей, чтобы перебор не рос
    if (best >= 0 && (bestDistance <= gap || groups.size / GROUP_STRIDE >= MAX_RAW_GROUPS)) {
      merge(best * GROUP_STRIDE, e, i * 4, 1f)
    } else {
      for (k in 0 until 4) groups.add(e[i * 4 + k])
      groups.add(1f)
    }
  }
  // сливаем близкие группы, затем лишние — по ближайшей паре
  while (true) {
    val n = groups.size / GROUP_STRIDE
    var pa = -1
    var pb = -1
    var nearest = Float.POSITIVE_INFINITY
    for (a in 0 until n) for (b in a + 1 until n) {
      val d = distance(groups.items, a * GROUP_STRIDE, groups.items, b * GROUP_STRIDE)
      if (d < nearest) { nearest = d; pa = a; pb = b }
    }
    if (pa < 0 || (nearest > gap && n <= MAX_GROUPS)) break
    merge(pa * GROUP_STRIDE, groups.items, pb * GROUP_STRIDE, groups.items[pb * GROUP_STRIDE + 4])
    groups.removeRange(pb * GROUP_STRIDE, GROUP_STRIDE)
  }
  return groups.items.copyOf(groups.size)
}

private class Box {
  var minX = 0f; var minY = 0f; var minZ = 0f
  var maxX = 0f; var maxY = 0f; var maxZ = 0f

  init { clear() }

  val isEmpty: Boolean get() = minX > maxX
  val centerX: Float get() = (minX + maxX) / 2
  val centerZ: Float get() = (minZ + maxZ) / 2

  fun clear() {
    minX = Float.POSITIVE_INFINITY; minY = Float.POSITIVE_INFINITY; minZ = Float.POSITIVE_INFINITY
    maxX = Float.NEGATIVE_INFINITY; maxY = Float.NEGATIVE_INFINITY; maxZ = Float.NEGATIVE_INFINITY
  }

  fun add(x: Float, y: Float, z: Float) {
    if (!x.isFinite() || !y.isFinite() || !z.isFinite()) return
    if (x < minX) minX = x
    if (y < minY) minY = y
    if (z < minZ) minZ = z
    if (x > maxX) maxX = x
    if (y > maxY) maxY = y
    if (z > maxZ) maxZ = z
  }

  fun add(p: FloatArray, vertex: Int) = add(p[vertex * 3], p[vertex * 3 + 1], p[vertex * 3 + 2])

  fun addAll(p: FloatArray) {
    for (i in 0 until p.size / 3) add(p, i)
  }

  fun add(b: Box) {
    if (b.minX < minX) minX = b.minX
    if (b.minY < minY) minY = b.minY
    if (b.minZ < minZ) minZ = b.minZ
    if (b.maxX > maxX) maxX = b.maxX
    if (b.maxY > maxY) maxY = b.maxY
    if (b.maxZ > maxZ) maxZ = b.maxZ
  }

  /**
   * Элемент [b] в кадр с окном [x0]..[x1] × [z0]..[z1]: центр в окне — целиком (true), иначе только его часть
   * внутри окна (длинная линия, начатая у основной массы, уходит за край, а не пропадает) — false, это выброс.
   */
  fun addInWindow(b: Box, x0: Float, x1: Float, z0: Float, z1: Float): Boolean {
    if (b.centerX in x0..x1 && b.centerZ in z0..z1) {
      add(b)
      return true
    }
    val ax = max(b.minX, x0); val bx = minOf(b.maxX, x1)
    val az = max(b.minZ, z0); val bz = minOf(b.maxZ, z1)
    if (ax <= bx && az <= bz) {
      if (ax < minX) minX = ax
      if (bx > maxX) maxX = bx
      if (az < minZ) minZ = az
      if (bz > maxZ) maxZ = bz
      if (b.minY < minY) minY = b.minY
      if (b.maxY > maxY) maxY = b.maxY
    }
    return false
  }

  /** Расширить габариты объекта в [a] с [o] (шесть чисел) этим. */
  fun into(a: FloatArray, o: Int) {
    if (minX < a[o]) a[o] = minX
    if (minY < a[o + 1]) a[o + 1] = minY
    if (minZ < a[o + 2]) a[o + 2] = minZ
    if (maxX > a[o + 3]) a[o + 3] = maxX
    if (maxY > a[o + 4]) a[o + 4] = maxY
    if (maxZ > a[o + 5]) a[o + 5] = maxZ
  }

  fun set(a: FloatArray, o: Int) {
    minX = a[o]; minY = a[o + 1]; minZ = a[o + 2]
    maxX = a[o + 3]; maxY = a[o + 4]; maxZ = a[o + 5]
  }
}

/** Растущий массив чисел без упаковки. */
private class FloatList {
  var items = FloatArray(64)
    private set
  var size = 0
    private set

  fun add(v: Float) {
    if (size == items.size) items = items.copyOf(size * 2)
    items[size++] = v
  }

  fun removeRange(from: Int, count: Int) {
    items.copyInto(items, from, from + count, size)
    size -= count
  }
}

/** Счёт центров по [BINS] равным бинам от [min] до [max]; вне диапазона — в крайний бин. */
private class Histogram(private val min: Float, max: Float) {
  private val bin = max((max - min) / BINS, 1e-6f)
  private val counts = IntArray(BINS)

  fun add(v: Float) {
    counts[((v - min) / bin).toInt().coerceIn(0, BINS - 1)]++
  }

  /** Окно без [skip] крайних центров с каждой стороны (по краям бинов), раздвинутое на [WINDOW_MARGIN] своей ширины. */
  fun window(skip: Int): Pair<Float, Float> {
    var lo = 0
    var seen = 0
    while (lo < BINS - 1 && seen + counts[lo] <= skip) seen += counts[lo++]
    var hi = BINS - 1
    seen = 0
    while (hi > lo && seen + counts[hi] <= skip) seen += counts[hi--]
    val from = min + lo * bin
    val to = min + (hi + 1) * bin
    val margin = (to - from) * WINDOW_MARGIN
    return Pair(from - margin, to + margin)
  }
}

private const val BINS = 4096
private const val OUTLIER_SHARE = 0.01f
private const val WINDOW_MARGIN = 0.5f
private const val MIN_AREA_RATIO = 25f
private const val MIN_SIDE_SHARE = 0.01f
// меньше элементов — выбросы не ищутся: на паре объектов не понять, какой из них лишний
private const val MIN_ELEMENTS = 10
// примитивов в выборке первой проверки не больше
private const val SAMPLE_LIMIT = 32768
// групп выбросов не больше: указателей у края всё равно не больше трёх
private const val MAX_GROUPS = 8
private const val MAX_RAW_GROUPS = 64
private const val GROUP_STRIDE = 5
