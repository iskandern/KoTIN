package dev.iskandern.kotin

import dev.iskandern.kotin.model.NO_OBJECT
import dev.iskandern.kotin.scene.boundaryEdges
import dev.iskandern.kotin.scene.MAX_TEXT_DP
import dev.iskandern.kotin.scene.MIN_LINE_WIDTH_PX
import dev.iskandern.kotin.scene.MIN_MARKER_DP
import dev.iskandern.kotin.scene.READABLE_TEXT_DP
import dev.iskandern.kotin.scene.RenderLabel
import dev.iskandern.kotin.scene.RenderPoint
import dev.iskandern.kotin.scene.RenderScene
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Луч через точку экрана и оси камеры — всё в координатах сцены с вертикальным масштабом, как на экране: вершины
 * сцены масштабируются по Y при сравнении, а не наоборот, потому что перпендикуляр к лучу при неравномерном
 * масштабе не сохраняется. Направление, [right], [up] и [back] единичные.
 */
class PickRay(
  val ox: Double, val oy: Double, val oz: Double,
  val dx: Double, val dy: Double, val dz: Double,
  /** Оси экрана: вправо, вверх и от сцены к камере. */
  val rightX: Double, val rightY: Double, val rightZ: Double,
  val upX: Double, val upY: Double, val upZ: Double,
  val backX: Double, val backY: Double, val backZ: Double,
  /** Размер пикселя на единичной глубине: 2·tg(fov/2) / высота экрана. */
  val pixelSlope: Double,
)

/**
 * Объект под лучом: расстояние от глаза до места попадания вдоль луча, класс по размеру ([RANK_POINT]…[RANK_TERRAIN]) и
 * место попадания в координатах сцены ([y] — с вертикальным масштабом) — по нему номер кандидата ставится на экран.
 */
class PickHit(val objectId: Int, val distance: Double, val rank: Int = RANK_TERRAIN, val x: Float = 0f, val y: Float = 0f, val z: Float = 0f)

/**
 * Классы объектов по месту на экране — порядок кандидатов под нажатием: сначала то, что занимает меньше места и во что
 * труднее попасть (значок точки), в конце — рельеф, в который попадает любое нажатие. Внутри класса — по оценке.
 */
const val RANK_POINT = 0
const val RANK_LABEL = 1
const val RANK_LINE = 2
/** Заливки чертежа и рукотворные сетки (трубы, колодцы). */
const val RANK_FILL = 3
const val RANK_TERRAIN = 4

/** Кандидатов под нажатием — не больше стольких. */
const val MAX_CANDIDATES = 8

/** Кандидат попадает в список, если его оценка (глубина с поправками) не хуже лучшей больше чем на эту долю. */
private const val AMBIGUITY = 0.05

/**
 * Радиус попадания в отрезок или точку, dp: палец не бывает точным, а линия в 1–2 px иначе не ловится. Ту же
 * окрестность получают точки и подписи; у толстой линии берётся её полуширина, если она больше.
 */
const val PICK_RADIUS_DP = 10f

/** Радиус попадания в значок точки, dp: вдвое больше линейного — на телефоне палец закрывает значок целиком. */
const val POINT_PICK_RADIUS_DP = PICK_RADIUS_DP * 2

/**
 * Перед выбором из объектов под курсором в плане важнее тот, что ближе к лучу, чем тот, что на пару процентов
 * ближе к камере: на наклонном плане 12 px по экрану — это единицы процентов разницы глубины. Вес промаха
 * (0 — по лучу, 1 — на краю окрестности) — доля глубины.
 */
private const val LATERAL_WEIGHT = 0.05

// Линии, точки и подписи рисуются поверх поверхностей с запасом по глубине (см. SceneView): при выборе они так же
// выигрывают у того, что лежит под ними на одной глубине. Подпись и значок — плашки, обращённые к камере: при
// наклонной камере рельеф под нижним краем плашки ближе её плоскости, поэтому видимая плашка сравнивается с рельефом
// по его же глубине под лучом ([Picker.terrainT]), и побеждает по этому смещению. Заливки — между ними, и порядок
// прорисовки заливок (до MAX_FILL_STEPS ступеней) разрешает перекрытия: больший порядок рисуется поверх.
private const val WIRE_BIAS = 0.01
private const val FILL_BIAS = 0.005
private const val FILL_ORDER_BIAS = 0.00002

private const val LABEL_CHAR_WIDTH = 0.6
private const val LABEL_LINE_HEIGHT = 1.3

/** Лучший объект под [ray] — первый из [pickCandidates]; null — под лучом ничего нет. */
fun pickObject(scene: RenderScene, settings: ViewSettings, ray: PickRay, density: Float = 1f): PickHit? =
  pickCandidates(scene, settings, ray, density).firstOrNull()

/**
 * Объекты под [ray], лучший первым: выбирается только то, что показано при [settings] (скрытые слои, выключенные виды,
 * подписи и точки за поверхностью при «Скрывать за поверхностью» не выбираются). Видимые примитивы с объектом
 * оцениваются по близости к камере, с поправками на то, что линии и точки легли поверх своей поверхности
 * ([WIRE_BIAS]), а промах мимо линии — на штраф ([LATERAL_WEIGHT]); у объекта — лучшая из оценок его примитивов.
 * В список идут объекты с оценкой не хуже лучшей на [AMBIGUITY], не больше [MAX_CANDIDATES], по классу размера
 * ([PickHit.rank]: точки, подписи, линии, заливки, рельеф) и внутри него по оценке — в точку попасть труднее, чем в
 * рельеф под ней; рельеф остаётся в списке, только когда других кандидатов нет (иначе он был бы вторым при любом
 * нажатии на точку). Перебор прямой, без индекса: выбор по долгому нажатию, а не на каждое движение. [density] —
 * пикселей в dp: от него радиус попадания и размер подписей на экране.
 */
fun pickCandidates(scene: RenderScene, settings: ViewSettings, ray: PickRay, density: Float = 1f): List<PickHit> {
  if (scene.objects.isEmpty()) return emptyList()
  val picker = Picker(ray, settings.verticalScale.toDouble(), density.toDouble())
  val hidden = settings.hiddenLayers

  // подписи и точки прячутся за рельефом тестом глубины (то же условие, что у материала в SceneView): поверхность тогда
  // загораживает их и при выборе, даже если сама выбрать нечем (у неё нет объекта)
  val occlusion = settings.showSurface && settings.hideOccluded && !scene.drawingFlattened &&
    (scene.labels.isNotEmpty() || scene.points.isNotEmpty())
  picker.occlusion = occlusion
  // трубы (рукотворные сетки) включаются своей галочкой и рельеф не закрывают от подписей: он — только terrain
  for (surface in scene.surfaces) {
    if (surface.layer in hidden) continue
    if (!(if (surface.structure) settings.showStructures else settings.showSurface)) continue
    val occluder = occlusion && !surface.structure
    val hasObjects = surface.triangleObjects != null || surface.objectId != NO_OBJECT
    if (!hasObjects && !occluder) continue
    val p = surface.positions
    val ix = surface.indices
    for (tri in 0 until surface.triangleCount) {
      val id = surface.objectOf(tri)
      if (id == NO_OBJECT && !occluder) continue
      picker.triangle(id, p, ix[tri * 3] * 3, ix[tri * 3 + 1] * 3, ix[tri * 3 + 2] * 3, 0.0, occluder = occluder, terrain = !surface.structure)
    }
  }
  if (!settings.showSurface && settings.showWire) {
    // поверхность не показана, виден только каркас: выбираются его рёбра, как линии. Рёбра не знают своего треугольника,
    // поэтому так — только у поверхностей целиком одного объекта
    val radius = PICK_RADIUS_DP * picker.density * ray.pixelSlope
    for (surface in scene.surfaces) {
      if (surface.structure || surface.layer in hidden || surface.triangleObjects != null || surface.objectId == NO_OBJECT) continue
      val e = surface.edges
      for (edge in 0 until e.size / 2) picker.segment(surface.objectId, surface.positions, e[edge * 2] * 3, e[edge * 2 + 1] * 3, radius)
    }
  }

  if (settings.showLines) {
    for (fill in scene.fills) {
      val ids = fill.objectIds ?: continue
      if (fill.layer in hidden) continue
      val p = fill.positions
      val ix = fill.indices
      for (tri in 0 until fill.triangleCount) {
        val id = ids[tri]
        if (id == NO_OBJECT) continue
        val order = fill.steps[ix[tri * 3]].toDouble()
        picker.triangle(id, p, ix[tri * 3] * 3, ix[tri * 3 + 1] * 3, ix[tri * 3 + 2] * 3, FILL_BIAS + order * FILL_ORDER_BIAS, rank = RANK_FILL)
      }
    }
  }

  for (lines in scene.lines) {
    val ids = lines.objectIds ?: continue
    val visible = if (lines.pointThickness) settings.showPoints && settings.showPointThickness else settings.showLines
    if (!visible || lines.layer in hidden) continue
    val halfWidth = if (settings.showLineWeights) lines.widthPx / 2.0 else 0.5
    val radius = max(halfWidth, PICK_RADIUS_DP * picker.density) * ray.pixelSlope
    val p = lines.positions
    val ix = lines.indices
    for (s in 0 until lines.lineCount) {
      val id = ids[s]
      if (id == NO_OBJECT) continue
      picker.segment(id, p, ix[s * 2] * 3, ix[s * 2 + 1] * 3, radius)
    }
  }

  if (settings.showPoints) {
    for (point in scene.points) {
      if (point.objectId == NO_OBJECT || point.layer in hidden) continue
      picker.anchor(point.objectId, point.x.toDouble(), point.y.toDouble(), point.z.toDouble(), POINT_PICK_RADIUS_DP * picker.density * ray.pixelSlope)
    }
  }

  if (settings.showLabels) {
    for (label in scene.labels) {
      if (label.objectId == NO_OBJECT || label.layer in hidden) continue
      picker.label(label, settings.thinLabels)
    }
  }

  return picker.candidates()
}

/**
 * Пересечение [ray] с треугольником вершин [a], [b], [c] (индексы в [p], по три числа; Y умножается на вертикальный
 * масштаб [k]): Мёллер — Трамбор в двустороннем виде. Расстояние вдоль луча до попадания; не больше нуля — мимо.
 */
internal fun rayTriangle(ray: PickRay, k: Double, p: FloatArray, a: Int, b: Int, c: Int): Double {
  val ax = p[a].toDouble(); val ay = p[a + 1] * k; val az = p[a + 2].toDouble()
  val e1x = p[b] - ax; val e1y = p[b + 1] * k - ay; val e1z = p[b + 2] - az
  val e2x = p[c] - ax; val e2y = p[c + 1] * k - ay; val e2z = p[c + 2] - az
  val px = ray.dy * e2z - ray.dz * e2y
  val py = ray.dz * e2x - ray.dx * e2z
  val pz = ray.dx * e2y - ray.dy * e2x
  val det = e1x * px + e1y * py + e1z * pz
  if (det == 0.0) return -1.0
  val inv = 1.0 / det
  val tx = ray.ox - ax; val ty = ray.oy - ay; val tz = ray.oz - az
  val u = (tx * px + ty * py + tz * pz) * inv
  if (u < 0.0 || u > 1.0) return -1.0
  val qx = ty * e1z - tz * e1y
  val qy = tz * e1x - tx * e1z
  val qz = tx * e1y - ty * e1x
  val v = (ray.dx * qx + ray.dy * qy + ray.dz * qz) * inv
  if (v < 0.0 || u + v > 1.0) return -1.0
  return (e2x * qx + e2y * qy + e2z * qz) * inv
}

private class Picker(val ray: PickRay, val k: Double, val density: Double) {
  // лучшая оценка на объект: примитивы одного объекта (значок точки и её подпись) схлопываются в одного кандидата
  private class Candidate(var score: Double, var t: Double, var rank: Int, var x: Float, var y: Float, var z: Float)
  private val hits = HashMap<Int, Candidate>()

  /** Видимая поверхность загораживает подписи и точки за ней; [occluderT] — ближайшее попадание луча в неё. */
  var occlusion = false
  private var occluderT = Double.POSITIVE_INFINITY

  /** Ближайшее попадание луча в рельеф (без труб): видимые подписи и значки на нём не дальше него. */
  private var terrainT = Double.POSITIVE_INFINITY

  /**
   * Кандидаты по порядку: класс размера, затем оценка; не хуже лучшей на [AMBIGUITY], не больше [MAX_CANDIDATES];
   * рельеф — только если больше ничего нет.
   */
  fun candidates(): List<PickHit> {
    if (hits.isEmpty()) return emptyList()
    val best = hits.values.minOf { it.score }
    val close = hits.entries.filter { it.value.score <= best * (1 + AMBIGUITY) }
    val withoutTerrain = close.filter { it.value.rank < RANK_TERRAIN }
    return (if (withoutTerrain.isEmpty()) close else withoutTerrain)
      .sortedWith(compareBy({ it.value.rank }, { it.value.score }))
      .take(MAX_CANDIDATES)
      .map { (id, c) -> PickHit(id, c.t, c.rank, c.x, c.y, c.z) }
  }

  // при равных оценках побеждает нарисованный позже: он поверх; класс объекта — наименьший из классов его примитивов,
  // место — у примитива с лучшей оценкой в этом классе
  private fun offer(id: Int, t: Double, bias: Double, miss: Double, rank: Int, x: Float, y: Float, z: Float) {
    val score = t * (1.0 - bias) * (1.0 + LATERAL_WEIGHT * miss)
    val c = hits[id]
    if (c == null) {
      hits[id] = Candidate(score, t, rank, x, y, z)
      return
    }
    if (rank < c.rank || (rank == c.rank && score <= c.score)) {
      c.rank = rank
      c.x = x; c.y = y; c.z = z
    }
    if (score <= c.score) {
      c.score = score
      c.t = t
    }
  }

  /** Треугольник вершин [a], [b], [c] (индексы в [p], по три числа); [terrain] — рельеф, иначе заливка или сетка. */
  fun triangle(
    id: Int, p: FloatArray, a: Int, b: Int, c: Int, bias: Double, occluder: Boolean = false, terrain: Boolean = false,
    rank: Int = if (terrain) RANK_TERRAIN else RANK_FILL,
  ) {
    val t = rayTriangle(ray, k, p, a, b, c)
    if (t <= 0.0) return
    if (occluder && t < occluderT) occluderT = t
    if (terrain && t < terrainT) terrainT = t
    if (id != NO_OBJECT) {
      offer(id, t, bias, 0.0, rank, (ray.ox + ray.dx * t).toFloat(), (ray.oy + ray.dy * t).toFloat(), (ray.oz + ray.dz * t).toFloat())
    }
  }

  /** Отрезок с концами [a] и [b] (индексы в [p]): ближайшие точки луча и отрезка не дальше [radius] на единице глубины. */
  fun segment(id: Int, p: FloatArray, a: Int, b: Int, radius: Double) {
    val sx = p[a].toDouble(); val sy = p[a + 1] * k; val sz = p[a + 2].toDouble()
    val vx = p[b] - sx; val vy = p[b + 1] * k - sy; val vz = p[b + 2] - sz
    val wx = ray.ox - sx; val wy = ray.oy - sy; val wz = ray.oz - sz
    val bb = ray.dx * vx + ray.dy * vy + ray.dz * vz
    val c = vx * vx + vy * vy + vz * vz
    val d = ray.dx * wx + ray.dy * wy + ray.dz * wz
    val e = vx * wx + vy * wy + vz * wz
    val denom = c - bb * bb
    // луч параллелен отрезку (denom ≈ 0): любая точка отрезка, берём начало
    var s = if (denom > c * PARALLEL_EPS) ((e - bb * d) / denom).coerceIn(0.0, 1.0) else 0.0
    var t = s * bb - d
    if (t < 0.0) {
      // ближайшая точка луча позади глаза: берём начало луча и ближайшую к нему точку отрезка
      t = 0.0
      s = if (c > 0.0) (e / c).coerceIn(0.0, 1.0) else 0.0
    }
    val cx = sx + vx * s
    val cy = sy + vy * s
    val cz = sz + vz * s
    val rx = ray.ox + ray.dx * t - cx
    val ry = ray.oy + ray.dy * t - cy
    val rz = ray.oz + ray.dz * t - cz
    val reach = radius * t
    val miss2 = rx * rx + ry * ry + rz * rz
    if (t <= 0.0 || miss2 > reach * reach) return
    offer(id, t, WIRE_BIAS, sqrt(miss2) / reach, RANK_LINE, cx.toFloat(), cy.toFloat(), cz.toFloat())
  }

  /** Точка (x, y, z) сцены: попадание, если луч прошёл от неё не дальше [radius] на единице глубины. */
  fun anchor(id: Int, x: Double, y: Double, z: Double, radius: Double) {
    val rx = x - ray.ox; val ry = y * k - ray.oy; val rz = z - ray.oz
    val t = rx * ray.dx + ry * ray.dy + rz * ray.dz
    if (t <= 0.0) return
    val miss2 = rx * rx + ry * ry + rz * rz - t * t
    val reach = radius * t
    if (miss2 > reach * reach) return
    // значок шейдер подтягивает к камере примерно на свой размер: за поверхностью — только глубже этого запаса
    if (occlusion && t - reach * OCCLUSION_SLACK > occluderT) return
    offer(id, min(t, terrainT), WIRE_BIAS, sqrt(max(miss2, 0.0)) / reach, RANK_POINT, x.toFloat(), (y * k).toFloat(), z.toFloat())
  }

  /**
   * Подпись: шейдер раскладывает её в плоскости экрана через точку вставки, размер в пикселях (настоящий, но не
   * меньше значка и не больше предела), строка идёт вдоль проекции направления подписи (у
   * [RenderLabel.screenHorizontal] — горизонтально). Поэтому и попадание — в этой плоскости, в пикселях. Ширина
   * строки оценивается по числу знаков: раскладку по шрифту знает только сцена. У подписей с кеглем в dp
   * (высота < 0, подписи LandXML) размер — кегль на экране, а подъём и сдвиг строки ([RenderLabel.liftDp],
   * [RenderLabel.shiftDp]) смещают прямоугольник, как в шейдере: иначе строки столбика в одной точке неразличимы.
   */
  fun label(label: RenderLabel, thin: Boolean) {
    val ax = label.x.toDouble(); val ay = label.y * k; val az = label.z.toDouble()
    val rx = ax - ray.ox; val ry = ay - ray.oy; val rz = az - ray.oz
    val depth = -(rx * ray.backX + ry * ray.backY + rz * ray.backZ)
    val facing = ray.dx * ray.backX + ray.dy * ray.backY + ray.dz * ray.backZ
    if (depth <= MIN_DEPTH || facing >= -1e-9) return
    // пересечение луча с плоскостью экрана через точку вставки
    val t = -depth / facing
    val hx = ray.ox + ray.dx * t - ax; val hy = ray.oy + ray.dy * t - ay; val hz = ray.oz + ray.dz * t - az
    val pixel = ray.pixelSlope * depth
    val screenX = (hx * ray.rightX + hy * ray.rightY + hz * ray.rightZ) / pixel
    val screenY = (hx * ray.upX + hy * ray.upY + hz * ray.upZ) / pixel

    val low = (if (thin) READABLE_TEXT_DP else MIN_MARKER_DP) * density
    val screenDp = label.height < 0f
    val sizePx = if (screenDp) -label.height * density else label.height / pixel
    val size = sizePx.coerceIn(low, max(low, MAX_TEXT_DP * density))
    // направление строки на экране; строка не переворачивается вверх ногами (как в шейдере);
    // screenHorizontal — строка горизонтальна на экране при любой камере
    val ax0 = if (label.screenHorizontal) 0f else label.alongX
    val az0 = if (label.screenHorizontal) 0f else label.alongZ
    var alongX = ax0 * ray.rightX + az0 * ray.rightZ
    var alongY = ax0 * ray.upX + az0 * ray.upZ
    val length = sqrt(alongX * alongX + alongY * alongY)
    if (length > 1e-4) { alongX /= length; alongY /= length } else { alongX = 1.0; alongY = 0.0 }
    if (alongX < 0.0) { alongX = -alongX; alongY = -alongY }
    val u = screenX * alongX + screenY * alongY - (if (screenDp) label.shiftDp * density else 0.0)
    val v = -screenX * alongY + screenY * alongX - (if (screenDp) label.liftDp * density else 0.0)

    // плашку подписи шейдер подтягивает к камере на 1,5 её высоты
    if (occlusion && t - size * pixel * OCCLUSION_SLACK > occluderT) return
    val box = LabelBox.of(label)
    val pad = PICK_RADIUS_DP * density * 0.5
    if (u < box.uMin * size - pad || u > box.uMax * size + pad || v < box.vMin * size - pad || v > box.vMax * size + pad) return
    offer(label.objectId, min(t, terrainT), WIRE_BIAS, 0.0, RANK_LABEL, ax.toFloat(), ay.toFloat(), az.toFloat())
  }

  private companion object {
    const val PARALLEL_EPS = 1e-12
    const val OCCLUSION_SLACK = 1.5
    const val MIN_DEPTH = 1e-6
  }
}

/**
 * Прямоугольник подписи в долях её высоты: вдоль строки от [uMin] до [uMax], поперёк (вверх) от [vMin] до [vMax],
 * от точки вставки. Оценка по числу знаков: ширина знака [LABEL_CHAR_WIDTH] высоты, строка — [LABEL_LINE_HEIGHT].
 */
internal class LabelBox(val uMin: Double, val uMax: Double, val vMin: Double, val vMax: Double) {
  companion object {
    fun of(label: RenderLabel): LabelBox {
      val height = max(label.height.toDouble(), 1e-6)
      val frame = label.frame
      // самая длинная строка и число строк по переводам строки
      var longest = 0
      var current = 0
      var lines = 1
      for (ch in label.text) {
        if (ch == '\n') { lines++; current = 0 } else { current++; longest = max(longest, current) }
      }
      val natural = longest * LABEL_CHAR_WIDTH * label.widthFactor
      if (frame != null) {
        // MTEXT: ширина рамки, строки переносятся по ней; привязка — 1…9 (3×3: сверху / по центру / снизу, слева / по центру / справа)
        val width = if (frame.width > 0.0) frame.width / height else natural
        val wrapped = if (width > 0.0) ceil(natural * lines / width).toInt() else lines
        val block = if (frame.height > 0.0) frame.height / height else max(lines, wrapped) * LABEL_LINE_HEIGHT
        val column = ((frame.attachment - 1).coerceIn(0, 8)) % 3
        val row = ((frame.attachment - 1).coerceIn(0, 8)) / 3
        val u0 = when (column) { 0 -> 0.0; 1 -> -width / 2; else -> -width }
        val v0 = when (row) { 0 -> -block; 1 -> -block / 2; else -> 0.0 }
        return LabelBox(u0, u0 + width, v0, v0 + block)
      }
      val width = if (label.fitLength > 0f) label.fitLength / height else natural
      val block = lines * LABEL_LINE_HEIGHT
      val u0 = when (label.alignH) { 1, 4 -> -width / 2; 2 -> -width; else -> 0.0 }
      val v0 = when (label.alignV) { 1 -> 0.0; 2 -> -block / 2; 3 -> -block; else -> -0.25 }
      return LabelBox(u0, u0 + width, v0, v0 + block)
    }
  }
}

/**
 * Геометрия выбранного объекта [objectId] в координатах сцены (без вертикального масштаба) — для подсветки:
 * [segments] — отрезки по шесть чисел, [triangles] — треугольники по девять, [anchors] — точки вставки подписей без
 * геометрии по три числа, [points] — точки объекта (подсвечиваются своим значком). Поверхность целиком (объект на всю сетку) сюда не копируется: её подкрашивают параметром
 * материала, дубликат геометрии не нужен. [truncated] — треугольников больше предела [MAX_HIGHLIGHT_TRIANGLES],
 * показаны не все.
 */
class SelectionGeometry(
  val objectId: Int,
  val segments: FloatArray,
  val triangles: FloatArray,
  val anchors: FloatArray,
  val truncated: Boolean,
  /** Из каких видов примитивов состоит объект: набор битов [PART_SURFACE], [PART_FILL], [PART_LINES], [PART_LABELS], [PART_POINTS]. */
  val parts: Int = 0,
  /**
   * Подписи объекта — для подсветки текстом. Если у объекта есть линии или треугольники, подпись у него не точка
   * применения (значение на участке), и кольцо вокруг неё не рисуется: в [anchors] такие подписи не попадают.
   */
  val labels: List<RenderLabel> = emptyList(),
  val points: List<RenderPoint> = emptyList(),
  /** Цвет и толщина (px, [RenderLines.widthPx]) линий объекта — для копии линии поверх обводки; первой найденной группы. */
  val lineRgb: Int = 0,
  val lineWidthPx: Float = MIN_LINE_WIDTH_PX,
  /**
   * Контур заливок объекта (штриховка, полилиния с шириной) отрезками по шесть чисел: обводится, как линия, —
   * полупрозрачный жёлтый поверх заливки своего цвета (жёлтой, оранжевой) почти не виден.
   */
  val outline: FloatArray = FloatArray(0),
) {
  val isEmpty: Boolean get() = segments.isEmpty() && triangles.isEmpty() && anchors.isEmpty() && points.isEmpty()

  /**
   * Показан ли объект при [settings] хотя бы одной частью: у выключенного вида и скрытого слоя ([layer]) объект не
   * выбирается, а уже выбранный перестаёт быть нужен.
   */
  fun isVisibleIn(settings: ViewSettings, layer: String): Boolean {
    if (layer in settings.hiddenLayers) return false
    fun has(part: Int) = parts and part != 0
    return (has(PART_SURFACE) && (settings.showSurface || settings.showWire)) ||
      (has(PART_STRUCTURE) && settings.showStructures) ||
      (has(PART_FILL) && settings.showLines) ||
      (has(PART_LINES) && settings.showLines) ||
      (has(PART_LABELS) && settings.showLabels) ||
      (has(PART_POINTS) && settings.showPoints)
  }
}

const val PART_SURFACE = 1
const val PART_FILL = 2
const val PART_LINES = 4
const val PART_LABELS = 8
const val PART_POINTS = 16
const val PART_STRUCTURE = 32

/** Подсветка текстом — не больше стольких подписей объекта. */
const val MAX_HIGHLIGHT_LABELS = 200

/** Подсветка закрашивает не больше стольких треугольников объекта: у поверхности их миллионы, а видно и так. */
const val MAX_HIGHLIGHT_TRIANGLES = 200_000

/**
 * Собирает примитивы объекта [objectId] — перебором всей сцены, один раз на выбор. Объект на всю поверхность
 * ([RenderSurface.objectId], без объектов по треугольникам) только отмечается в [SelectionGeometry.parts]: подсветка
 * подкрашивает её материал, а не рисует копию.
 */
fun collectObject(scene: RenderScene, objectId: Int): SelectionGeometry {
  val segments = FloatList()
  val triangles = FloatList()
  val anchors = FloatList()
  var triangleCount = 0
  var truncated = false
  var parts = 0

  fun addTriangle(p: FloatArray, a: Int, b: Int, c: Int, part: Int) {
    parts = parts or part
    if (triangleCount >= MAX_HIGHLIGHT_TRIANGLES) { truncated = true; return }
    triangleCount++
    for (i in 0 until 3) triangles.add(p[a + i])
    for (i in 0 until 3) triangles.add(p[b + i])
    for (i in 0 until 3) triangles.add(p[c + i])
  }

  for (surface in scene.surfaces) {
    if (surface.triangleObjects == null) {
      if (surface.objectId == objectId) parts = parts or if (surface.structure) PART_STRUCTURE else PART_SURFACE
      continue
    }
    val ix = surface.indices
    for (tri in 0 until surface.triangleCount) {
      if (surface.objectOf(tri) == objectId) addTriangle(surface.positions, ix[tri * 3] * 3, ix[tri * 3 + 1] * 3, ix[tri * 3 + 2] * 3, if (surface.structure) PART_STRUCTURE else PART_SURFACE)
    }
  }
  val outline = FloatList()
  for (fill in scene.fills) {
    val ids = fill.objectIds ?: continue
    val ix = fill.indices
    val own = ArrayList<Int>()
    for (tri in 0 until fill.triangleCount) {
      if (ids[tri] != objectId) continue
      addTriangle(fill.positions, ix[tri * 3] * 3, ix[tri * 3 + 1] * 3, ix[tri * 3 + 2] * 3, PART_FILL)
      own.add(tri)
    }
    if (own.isEmpty()) continue
    // контур — рёбра, которые не делят соседние треугольники объекта. Треугольники заливок вершины не делят — вершины
    // склеиваются по координатам, иначе внутренние рёбра тоже вышли бы краем
    val p = fill.positions
    val canonical = HashMap<Triple<Float, Float, Float>, Int>()
    val first = ArrayList<Int>()
    val ownIndices = IntArray(own.size * 3) {
      val v = ix[own[it / 3] * 3 + it % 3]
      canonical.getOrPut(Triple(p[v * 3], p[v * 3 + 1], p[v * 3 + 2])) { first.add(v); first.size - 1 }
    }
    for (e in boundaryEdges(ownIndices)) {
      val v = first[e]
      for (i in 0 until 3) outline.add(p[v * 3 + i])
    }
  }
  var lineRgb = 0
  var lineWidthPx = MIN_LINE_WIDTH_PX
  for (lines in scene.lines) {
    val ids = lines.objectIds ?: continue
    val p = lines.positions
    val ix = lines.indices
    for (s in 0 until lines.lineCount) {
      if (ids[s] != objectId) continue
      // толщина точек показывается вместе с точками
      parts = parts or (if (lines.pointThickness) PART_POINTS else PART_LINES)
      if (segments.isEmpty()) { lineRgb = lines.color; lineWidthPx = lines.widthPx }
      for (end in 0 until 2) for (i in 0 until 3) segments.add(p[ix[s * 2 + end] * 3 + i])
    }
  }
  val points = ArrayList<RenderPoint>()
  for (point in scene.points) {
    if (point.objectId == objectId) { parts = parts or PART_POINTS; points.add(point) }
  }
  val hasShape = !segments.isEmpty() || !triangles.isEmpty()
  val labels = ArrayList<RenderLabel>()
  for (label in scene.labels) {
    if (label.objectId != objectId) continue
    parts = parts or PART_LABELS
    if (labels.size < MAX_HIGHLIGHT_LABELS) labels.add(label)
    if (!hasShape) { anchors.add(label.x); anchors.add(label.y); anchors.add(label.z) }
  }
  return SelectionGeometry(
    objectId, segments.toArray(), triangles.toArray(), anchors.toArray(), truncated, parts, labels, points, lineRgb, lineWidthPx,
    outline.toArray(),
  )
}

private class FloatList {
  private var data = FloatArray(64)
  private var size = 0

  fun add(value: Float) {
    if (size == data.size) data = data.copyOf(data.size * 2)
    data[size++] = value
  }

  fun isEmpty(): Boolean = size == 0

  fun toArray(): FloatArray = data.copyOf(size)
}
