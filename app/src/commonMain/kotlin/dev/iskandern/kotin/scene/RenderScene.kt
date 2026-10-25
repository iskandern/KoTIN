package dev.iskandern.kotin.scene

import dev.iskandern.kotin.dxf.DxfColors
import dev.iskandern.kotin.dxf.DxfTextBackground
import dev.iskandern.kotin.dxf.DxfTextRun
import dev.iskandern.kotin.dxf.FACE_DEFAULT
import dev.iskandern.kotin.dxf.MTextFrame
import dev.iskandern.kotin.dxf.POINT_MODE_UNSET
import dev.iskandern.kotin.model.ALPHA_OPAQUE
import dev.iskandern.kotin.model.EXTRUSION_NONE
import dev.iskandern.kotin.model.EXTRUSION_POINT
import dev.iskandern.kotin.model.FillBatch
import dev.iskandern.kotin.model.GrowingDoubleArray
import dev.iskandern.kotin.model.GrowingIntArray
import dev.iskandern.kotin.model.LineBatch
import dev.iskandern.kotin.model.MeshBuilder
import dev.iskandern.kotin.model.NO_OBJECT
import dev.iskandern.kotin.model.NO_TICK
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneLabel
import dev.iskandern.kotin.model.SceneMesh
import dev.iskandern.kotin.model.SceneObject
import dev.iskandern.kotin.model.toRadians
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Поверхность одного слоя, готовая к отрисовке. Мир Filament: Y вверх, поэтому
 * DXF (x — восток, y — север, z — высота) → (x, z, −y), со сдвигом в [RenderScene.originX] и т.д.:
 * координаты DXF в миллионах метров во float теряют сантиметры.
 */
class RenderSurface(
  val layer: String,
  val positions: FloatArray,
  val normals: FloatArray,
  // Плоские UV по габаритам (строятся в buildRenderScene), но нигде не читаются: поверхность красится цветами вершин
  // (bakeSurfaceColors), без текстуры. Остаток от текстурного материала — можно убрать вместе с расчётом.
  val uvs: FloatArray,
  val indices: IntArray,
  /** Уникальные рёбра треугольников для каркаса (пары индексов в [positions]). */
  val edges: IntArray,
  /** Номер объекта на каждый треугольник ([NO_OBJECT] — из [objectId]); null — у всех [objectId]. */
  val triangleObjects: IntArray? = null,
  /** Объект всей поверхности, если у треугольника своего нет. */
  val objectId: Int = NO_OBJECT,
  /** Рукотворная сетка (колодцы, трубы), а не рельеф: её показывает «Трубы», а не «Поверхность». */
  val structure: Boolean = false,
  /** Внешняя граница (пары индексов в [positions]) — обводка [OUTLINE_RGB] у TIN без своей границы; null — без обводки. */
  val outline: IntArray? = null,
) {
  val vertexCount: Int get() = positions.size / 3
  val triangleCount: Int get() = indices.size / 3

  /** Объект треугольника [triangle] ([NO_OBJECT] — никакому). */
  fun objectOf(triangle: Int): Int {
    val own = triangleObjects?.get(triangle) ?: NO_OBJECT
    return if (own != NO_OBJECT) own else objectId
  }
}

/** Отрезки одного слоя и цвета (0xRRGGBB): пары индексов в [positions]. */
class RenderLines(
  val layer: String, val color: Int, val positions: FloatArray, val indices: IntArray,
  /** Толщина на экране, px; 1 — тонкие GL-линии, больше — ленты ([buildRibbon]). */
  val widthPx: Float = MIN_LINE_WIDTH_PX,
  /** Толщина точек (код 39 у POINT): показывается отдельным переключателем вместе с точками. */
  val pointThickness: Boolean = false,
  /** Номер объекта на каждый отрезок (пара индексов); null — объектов нет. */
  val objectIds: IntArray? = null,
) {
  val lineCount: Int get() = indices.size / 2
}

/**
 * Закрашенные треугольники одного слоя и цвета (0xRRGGBB): позиции вершин (не склеены) и индексы 0, 1, 2, …
 * У градиента [colors] — цвет на каждую вершину. [steps] — ступень порядка прорисовки на вершину
 * (0…[RenderScene.fillSteps] − 1): чем больше, тем ближе к камере по глубине, т. е. поверх.
 * [alpha] — непрозрачность 0…255, меньше 255 — рисуется полупрозрачной.
 */
class RenderFills(
  val layer: String, val color: Int, val positions: FloatArray, val indices: IntArray, val colors: IntArray? = null,
  val steps: FloatArray = FloatArray(positions.size / 3),
  val alpha: Int = ALPHA_OPAQUE,
  /** Номер объекта на каждый треугольник; null — объектов нет. */
  val objectIds: IntArray? = null,
) {
  val triangleCount: Int get() = indices.size / 3
}

/**
 * Подпись в координатах сцены: начало базовой линии, высота букв, направление строки в горизонтальной плоскости
 * ([alongX], [alongZ] — единичный вектор). Рисуется поверх 3D (в Filament нет текста).
 */
class RenderLabel(
  val layer: String, val color: Int,
  val x: Float, val y: Float, val z: Float,
  val height: Float,
  val alongX: Float, val alongZ: Float,
  val text: String,
  /** Выравнивание относительно точки вставки, как у DXF TEXT (0 слева / 1 центр / 2 справа; 0 база / 1 низ / 2 середина / 3 верх). */
  val alignH: Int = 0, val alignV: Int = 0,
  /** Ширина букв (x-factor), наклон (тангенс угла), длина строки «по ширине»/«вписанный» (0 — нет). */
  val widthFactor: Float = 1f, val shear: Float = 0f, val fitLength: Float = 0f,
  /** Рамка MTEXT: [text] — абзацы целиком, на строки делится в [layoutMTextLabels]. */
  val frame: MTextFrame? = null,
  /** Фрагменты MTEXT (цвет, высота, линии; концы — индексы в [text]) и плашка под строками; null — нет. */
  val runs: List<DxfTextRun>? = null, val background: DxfTextBackground? = null,
  /** Гарнитура ([FACE_DEFAULT] — Golos Text, остальные — в DxfFonts.kt); поправка ширины замены уже в [widthFactor]. */
  val face: Int = FACE_DEFAULT,
  /** Объект, которому принадлежит подпись ([NO_OBJECT] — никакому). */
  val objectId: Int = NO_OBJECT,
  /** Строка горизонтальна на экране, [alongX]/[alongZ] на неё не влияют (см. [SceneLabel.screenHorizontal]). */
  val screenHorizontal: Boolean = false,
  /** Подъём над точкой и сдвиг вдоль строки, dp, и засечка под строкой — см. [SceneLabel.liftDp], [SceneLabel.tickRgb]. */
  val liftDp: Float = 0f,
  val shiftDp: Float = 0f,
  val tickRgb: Int = NO_TICK,
)

/** Точка: [alongX], [alongZ] — ось X значка в плоскости сцены, как у [RenderLabel]. */
class RenderPoint(
  val layer: String, val color: Int, val x: Float, val y: Float, val z: Float,
  val alongX: Float = 1f, val alongZ: Float = 0f,
  val objectId: Int = NO_OBJECT,
)

/**
 * Слой для списка: цвет из DXF (0xRRGGBB), видим ли по умолчанию (слой не выключен и не заморожен),
 * [group] — заголовок группы в списке (у DXF пустой).
 */
class RenderLayer(val name: String, val color: Int, val visibleByDefault: Boolean, val group: String = "")

class RenderScene(
  val surfaces: List<RenderSurface>,
  val lines: List<RenderLines>,
  val fills: List<RenderFills>,
  val labels: List<RenderLabel>,
  val points: List<RenderPoint>,
  val layerInfos: List<RenderLayer>,
  /** Раскрытие блоков остановлено на лимите — показана не вся графика. */
  val truncated: Boolean,
  /** Чертёж (линии, подписи, точки) положен на один уровень без своих отметок. */
  val drawingFlattened: Boolean,
  // Сдвиг сцены относительно DXF (центр габаритов); ни сцена, ни пикер, ни UI его не читают, только тесты передают.
  // Понадобится, чтобы вернуть координаты DXF (например, показать координаты выбранной точки).
  val originX: Double,
  val originY: Double,
  val originZ: Double,
  /** Полуразмеры сцены по осям мира. */
  val halfX: Float,
  val halfY: Float,
  val halfZ: Float,
  /** Вид и размер значка точек из `$PDMODE`/`$PDSIZE`; режим -1 — в файле не задан. */
  val pointMode: Int = POINT_MODE_UNSET,
  val pointSize: Float = 0f,
  /** Ступеней порядка прорисовки у заливок ([RenderFills.steps]); линии чертежа — над всеми. */
  val fillSteps: Int = 0,
  /** Не показанные грани тел (не плоские) и тела без читаемого SAT, см. [SceneGeometry]. */
  val hiddenBodyFaces: Int = 0,
  val unreadBodies: Int = 0,
  /** Сохранённый вид чертежа в координатах сцены; null — в файле не задан. */
  val savedView: RenderView? = null,
  /** `$LWDISPLAY`: переключатель «Толщина линий» при открытии файла. */
  val lineWeightDisplay: Boolean = true,
  /** Предлагать переключатель «Толщина линий» (см. [SceneGeometry.lineWeightsOffered]). */
  val lineWeightsOffered: Boolean = true,
  /** Выбираемые объекты: на них по номеру ссылаются `objectId` примитивов. */
  val objects: List<SceneObject> = emptyList(),
  /** «Чертёж в плоскость» изменил бы сцену: независимо от того, включён ли он сейчас. */
  val flattenChangesScene: Boolean = false,
  /**
   * Выпуклый контур чертежа в плане — для указателя на ушедший из кадра чертёж; у чертежа с далёкими объектами
   * ([home]) — контур основной массы без них. null — геометрии нет.
   */
  val outline: DrawingOutline? = null,
  /** Кадр исходного вида без далёких выбросов; null — по всей сцене. */
  val home: HomeFrame? = null,
) {
  val radius: Float get() = max(sqrt(halfX * halfX + halfY * halfY + halfZ * halfZ), 1f)
  val layers: List<String> get() = layerInfos.map { it.name }

  /**
   * Порядок слоёв по глубине при отрисовке: чем меньше площадь, тем ближе к камере (0 — самый большой слой).
   * В DXF одна и та же поверхность часто продублирована в разных слоях (вставка корпуса в общую поверхность)
   * с расхождением в сантиметры, и без разведения такие слои мигают при движении камеры (z-fighting).
   * Сдвиг глубины на шаг считает SceneView от ближней плоскости камеры; геометрия не смещается.
   */
  val surfaceRank: Map<RenderSurface, Int> by lazy {
    surfaces.sortedByDescending { it.planArea() }.withIndex().associate { (rank, surface) -> surface to rank }
  }
}

private fun RenderSurface.planArea(): Float {
  var minX = Float.POSITIVE_INFINITY
  var maxX = Float.NEGATIVE_INFINITY
  var minZ = Float.POSITIVE_INFINITY
  var maxZ = Float.NEGATIVE_INFINITY
  for (i in 0 until vertexCount) {
    minX = minOf(minX, positions[i * 3])
    maxX = maxOf(maxX, positions[i * 3])
    minZ = minOf(minZ, positions[i * 3 + 2])
    maxZ = maxOf(maxZ, positions[i * 3 + 2])
  }
  return (maxX - minX) * (maxZ - minZ)
}

/**
 * 2D-чертёж обычно лежит на z = 0, а поверхности — на реальных отметках (в сотнях метров выше). Чтобы чертёж
 * не висел далеко под поверхностью, плоские отрезки, тексты и точки поднимаются на нижний уровень поверхностей.
 *
 * [flattenDrawing] — положить на этот уровень весь чертёж, не только плоский. Бывает, что отметки в плане
 * бессмысленные (объекты внешних ссылок на ±1000 м при рельефе 600 м) — такой чертёж в 3D вытягивается в столб.
 * null — решить автоматически: да, если разброс отметок чертежа больше его размера в плане.
 */
fun buildRenderScene(geometry: SceneGeometry, flattenDrawing: Boolean? = null): RenderScene {
  val meshes = geometry.meshes.filter { it.triangleCount > 0 }
  val flatLevel = meshes.minOfOrNull { it.minZ } ?: 0.0
  val flatten = flattenDrawing ?: (geometry.flattenAuto && drawingLooksPlanar(geometry))
  // толщина (код 39) — объём над чертежом: у чертежа в плоскости её нет
  val lineBatches = if (flatten) geometry.lines.filter { it.extrusion == EXTRUSION_NONE } else geometry.lines
  val fillBatches = if (flatten) geometry.fills.filter { it.extrusion == EXTRUSION_NONE } else geometry.fills
  fun level(z: Double) = if (flatten || z == 0.0) flatLevel else z
  fun batchLevel(batch: LineBatch): Double? = if (batch.extrusion == EXTRUSION_NONE && (flatten || batch.flat)) flatLevel else null

  fun fillLevel(batch: FillBatch): Double? = if (batch.extrusion == EXTRUSION_NONE && (flatten || batch.flat)) flatLevel else null
  // толщина плоского чертежа стоит на его уровне: к отметке прибавляется уровень
  fun lift(extrusion: Int, flat: Boolean) = if (extrusion != EXTRUSION_NONE && flat) flatLevel else 0.0

  // XLINE и RAY в габариты не входят — разве что, кроме них, в файле ничего нет
  val boundedLines = lineBatches.filter { !it.unbounded }.ifEmpty { lineBatches }
  val bounds = Bounds()
  meshes.forEach { bounds.add(it) }
  boundedLines.forEach { bounds.add(it.coords, batchLevel(it), lift(it.extrusion, it.flat)) }
  fillBatches.forEach { bounds.add(it.coords, fillLevel(it), lift(it.extrusion, it.flat)) }
  geometry.labels.forEach { bounds.add(it.x, it.y, level(it.z)) }
  geometry.points.forEach { bounds.add(it.x, it.y, level(it.z)) }
  val layerInfos = geometry.layers.map { RenderLayer(it.name, dxfLayerColor(it.color, it.trueColor), it.isVisible, it.group) }
  if (bounds.isEmpty) {
    return RenderScene(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), layerInfos, geometry.truncated, flatten, 0.0, 0.0, 0.0, 1f, 1f, 1f, geometry.pointMode, geometry.pointSize.toFloat(),
      geometry.hiddenBodyFaces, geometry.unreadBodies)
  }

  // Центр и габариты сцены берутся по первой непустой группе: поверхности → точки, линии и заливки → подписи.
  // Далёкая подпись или поверхность не должны уводить камеру от основного содержимого.
  val fit = Bounds().also { b ->
    meshes.forEach { b.add(it) }
    if (b.isEmpty) {
      boundedLines.forEach { b.add(it.coords, batchLevel(it), lift(it.extrusion, it.flat)) }
      fillBatches.forEach { b.add(it.coords, fillLevel(it), lift(it.extrusion, it.flat)) }
      geometry.points.forEach { b.add(it.x, it.y, level(it.z)) }
    }
    if (b.isEmpty) geometry.labels.forEach { b.add(it.x, it.y, level(it.z)) }
  }
  val centroid = if (meshes.isEmpty()) null else surfaceCentroid(meshes)
  val cx = centroid?.get(0) ?: ((fit.minX + fit.maxX) / 2)
  val cy = centroid?.get(1) ?: ((fit.minY + fit.maxY) / 2)
  val cz = centroid?.get(2) ?: ((fit.minZ + fit.maxZ) / 2)
  val sizeX = max(fit.maxX - fit.minX, 1e-6)
  val sizeY = max(fit.maxY - fit.minY, 1e-6)

  val surfaces = meshes.map { mesh -> buildSurface(mesh, cx, cy, cz, fit.minX, fit.minY, sizeX, sizeY) }
  // пунктир — доли размера сцены: на обзоре сцена занимает ~500 px, штрих и промежуток — около 8 и 4 px, как в эталоне
  val dashOn = max(sizeX, sizeY) * DASH
  val lines = lineBatches.map { batch ->
    // длина в плане — по сплошной линии: после разбиения на штрихи промежутков в сцене уже нет
    if (batch.dashed) accumulatePlanLength(batch, geometry.objects)
    val dashed = if (batch.dashed) dashedBatch(batch, dashOn, dashOn * GAP / DASH) else batch
    buildLines(dashed, batchLevel(batch), lift(batch.extrusion, batch.flat), cx, cy, cz)
  }
  // порядок прорисовки заливок: номер заливки, сжатый до MAX_FILL_STEPS ступеней
  val fillCount = fillBatches.maxOfOrNull { batch -> batch.orders?.maxOrNull() ?: 0 }?.plus(1) ?: 0
  val fillSteps = minOf(fillCount, MAX_FILL_STEPS)
  val fills = fillBatches.map { batch ->
    buildFills(batch, fillLevel(batch), lift(batch.extrusion, batch.flat), cx, cy, cz) { order ->
      (order.toLong() * fillSteps / fillCount).toFloat()
    }
  }
  val labels = geometry.labels.map { label ->
    val radians = toRadians(label.rotationDeg)
    RenderLabel(
      layer = label.layer,
      color = label.color,
      x = (label.x - cx).toFloat(),
      y = (level(label.z) - cz).toFloat(),
      z = (cy - label.y).toFloat(),
      height = label.height.toFloat(),
      // ось Y чертежа (север) — это −Z сцены
      alongX = cos(radians).toFloat(),
      alongZ = -sin(radians).toFloat(),
      text = label.text,
      alignH = label.alignH,
      alignV = label.alignV,
      // замена шрифта со своей шириной букв (Arial Narrow — сжатый Arimo, SHX — растянутый узкий шрифт)
      widthFactor = (label.widthFactor * label.font.widthScale).toFloat(),
      // наклон больше ±85° — ошибка в файле, не растягиваем до бесконечности
      shear = tan(toRadians(label.obliqueDeg.coerceIn(-85.0, 85.0))).toFloat(),
      fitLength = label.fitLength.toFloat(),
      frame = label.frame,
      runs = label.runs,
      background = label.background,
      face = label.font.face,
      objectId = label.objectId,
      screenHorizontal = label.screenHorizontal,
      liftDp = label.liftDp.toFloat(),
      shiftDp = label.shiftDp.toFloat(),
      tickRgb = label.tickRgb,
    )
  }
  val points =geometry.points.map { point ->
    val radians = toRadians(point.rotationDeg)
    RenderPoint(
      point.layer, point.color, (point.x - cx).toFloat(), (level(point.z) - cz).toFloat(), (cy - point.y).toFloat(),
      alongX = cos(radians).toFloat(),
      alongZ = -sin(radians).toFloat(),
      objectId = point.objectId,
    )
  }

  val fitHalfX = max(fit.maxX - cx, cx - fit.minX).toFloat()
  val fitHalfY = max(fit.maxZ - cz, cz - fit.minZ).toFloat()
  val fitHalfZ = max(fit.maxY - cy, cy - fit.minY).toFloat()
  val outliersFrame = homeFrame(surfaces, lines, fills, points, labels, geometry.objects.size, fitHalfX, fitHalfZ)
  // у чертежа с далёкими объектами контур основной массы уже посчитан вместе с кадром
  val outline = outliersFrame?.outline ?: drawingOutline(surfaces, lines, fills, points, labels)
  // строки подписей, выступающие из кадра, расширяют его: исходный вид показывает их целиком
  val home = homeWithLabels(outliersFrame, labels, outline, fitHalfX, fitHalfY, fitHalfZ)
  return RenderScene(
    surfaces = surfaces,
    lines = lines,
    fills = fills,
    labels = labels,
    points = points,
    layerInfos = layerInfos,
    truncated = geometry.truncated,
    drawingFlattened = flatten,
    flattenChangesScene = geometry.flattenOffered && flattenChangesScene(geometry, flatLevel),
    originX = cx,
    originY = cy,
    originZ = cz,
    halfX = fitHalfX,
    halfY = fitHalfY,
    halfZ = fitHalfZ,
    pointMode = geometry.pointMode,
    pointSize = geometry.pointSize.toFloat(),
    fillSteps = fillSteps,
    hiddenBodyFaces = geometry.hiddenBodyFaces,
    unreadBodies = geometry.unreadBodies,
    lineWeightDisplay = geometry.lineWeightDisplay,
    lineWeightsOffered = geometry.lineWeightsOffered,
    objects = geometry.objects,
    savedView = geometry.savedView
      ?.let { RenderView((it.centerX - cx).toFloat(), (cy - it.centerY).toFloat(), it.height.toFloat()) }
      ?.takeIf { it.showsDrawing(outline, max(fit.maxX - cx, cx - fit.minX).toFloat(), max(fit.maxY - cy, cy - fit.minY).toFloat()) },
    outline = outline,
    home = home,
  )
}

/**
 * Ступеней порядка прорисовки заливок не больше этого: каждая — шаг буфера глубины ближе к камере (SceneView),
 * все вместе — в пределах потолка сдвига; при большем числе заливок соседние по порядку делят ступень.
 */
const val MAX_FILL_STEPS = 128

/** Сохранённый вид сверху: центр (x, z) в координатах сцены и высота охвата. */
class RenderView(val x: Float, val z: Float, val height: Float)

/**
 * Сохранённый вид показывает чертёж ±[halfX] × ±[halfZ] с контуром [outline]: центр кадра на чертеже (внутри контура)
 * или геометрия в середине кадра, видной при любой ширине экрана (до портретного телефона); и кадр не так велик,
 * чтобы чертёж в нём стал точкой. Иначе вид ведёт мимо чертежа — кнопки нет.
 */
internal fun RenderView.showsDrawing(outline: DrawingOutline?, halfX: Float, halfZ: Float): Boolean {
  if (outline == null || height > max(max(halfX, halfZ), 1f) * 2f * MAX_SAVED_VIEW_TO_DRAWING) return false
  if (insideHull(outline.hull, x, z)) return true
  val s = outline.samples
  for (i in 0 until s.size / 3) {
    if (abs(s[i * 3] - x) <= height * SAVED_VIEW_CORE_WIDTH && abs(s[i * 3 + 2] - z) <= height / 2f) return true
  }
  return false
}

/**
 * Точка ([x], [z]) внутри выпуклого контура [hull] (пары x, z) или на его границе; обход в любую сторону. Рёбра короче
 * тысячной размера контура пропускаются: соседние опорные прямые у угла чертежа дают почти совпадающие вершины, и
 * направление такого ребра — шум округления.
 */
private fun insideHull(hull: FloatArray, x: Float, z: Float): Boolean {
  val n = hull.size / 2
  if (n < 3) return false
  var area = 0f
  var size = 0f
  for (i in 0 until n) {
    val j = (i + 1) % n
    area += hull[i * 2] * hull[j * 2 + 1] - hull[j * 2] * hull[i * 2 + 1]
    size = max(size, max(abs(hull[i * 2]), abs(hull[i * 2 + 1])))
  }
  if (area == 0f) return false
  val minEdge = size * 1e-3f
  for (i in 0 until n) {
    val j = (i + 1) % n
    val ex = hull[j * 2] - hull[i * 2]
    val ez = hull[j * 2 + 1] - hull[i * 2 + 1]
    if (abs(ex) + abs(ez) < minEdge) continue
    val cross = ex * (z - hull[i * 2 + 1]) - ez * (x - hull[i * 2])
    if (cross * area < 0f) return false
  }
  return true
}

/** Половина ширины кадра в долях его высоты, что видна и на портретном телефоне (соотношение сторон ~0,45). */
private const val SAVED_VIEW_CORE_WIDTH = 0.2f

/** Во сколько раз высота сохранённого вида может превышать размер чертежа. */
private const val MAX_SAVED_VIEW_TO_DRAWING = 50f

/** Центроид поверхностей (x, y, z): средний по площади в плане центр треугольников всех сеток. */
private fun surfaceCentroid(meshes: List<SceneMesh>): DoubleArray {
  var sx = 0.0
  var sy = 0.0
  var sz = 0.0
  var sw = 0.0
  // запасной вариант для вырожденных сеток (нулевая площадь в плане): среднее по вершинам
  var vx = 0.0
  var vy = 0.0
  var vz = 0.0
  var vn = 0
  for (mesh in meshes) {
    val c = mesh.coords
    for (i in 0 until mesh.vertexCount) {
      vx += c[i * 3]; vy += c[i * 3 + 1]; vz += c[i * 3 + 2]
    }
    vn += mesh.vertexCount
    val t = mesh.triangles
    for (k in 0 until mesh.triangleCount) {
      val a = t[k * 3] * 3
      val b = t[k * 3 + 1] * 3
      val d = t[k * 3 + 2] * 3
      // сдвиг к первой вершине: площадь считается без потери точности на больших координатах
      val bx = c[b] - c[a]; val by = c[b + 1] - c[a + 1]
      val dx = c[d] - c[a]; val dy = c[d + 1] - c[a + 1]
      val w = kotlin.math.abs(bx * dy - by * dx) / 2
      sx += w * (c[a] + c[b] + c[d]) / 3
      sy += w * (c[a + 1] + c[b + 1] + c[d + 1]) / 3
      sz += w * (c[a + 2] + c[b + 2] + c[d + 2]) / 3
      sw += w
    }
  }
  return if (sw > 0.0) doubleArrayOf(sx / sw, sy / sw, sz / sw) else doubleArrayOf(vx / vn, vy / vn, vz / vn)
}

// «В плоскость» убирает толщину и переносит на flatLevel всё, что стоит не на нём; без флажка на него поднимается лишь z = 0
private fun flattenChangesScene(geometry: SceneGeometry, flatLevel: Double): Boolean {
  fun DoubleArray.offLevel(): Boolean = (0 until size / 3).any { this[it * 3 + 2] != flatLevel }
  return geometry.lines.any { it.extrusion != EXTRUSION_NONE || (!it.flat && it.coords.offLevel()) } ||
    geometry.fills.any { it.extrusion != EXTRUSION_NONE || (!it.flat && it.coords.offLevel()) } ||
    geometry.labels.any { it.z != 0.0 && it.z != flatLevel } ||
    geometry.points.any { it.z != 0.0 && it.z != flatLevel }
}

// разброс отметок чертежа больше его размера в плане — это не рельеф, отметки в плане не несут смысла
private fun drawingLooksPlanar(geometry: SceneGeometry): Boolean {
  val bounds = Bounds()
  // толщину не считаем: она прячется, когда чертёж положен в плоскость
  geometry.lines.forEach { if (it.extrusion == EXTRUSION_NONE && !it.unbounded) bounds.add(it.coords, null) }
  geometry.fills.forEach { if (it.extrusion == EXTRUSION_NONE) bounds.add(it.coords, null) }
  geometry.labels.forEach { bounds.add(it.x, it.y, it.z) }
  geometry.points.forEach { bounds.add(it.x, it.y, it.z) }
  if (bounds.isEmpty) return false
  val planSize = max(bounds.maxX - bounds.minX, bounds.maxY - bounds.minY)
  return bounds.maxZ - bounds.minZ > planSize
}

private fun dxfLayerColor(aci: Int, trueColor: Int?): Int =
  if (trueColor != null) trueColor and 0xFFFFFF else DxfColors.aciToRgb(aci)

private fun buildSurface(
  mesh: SceneMesh,
  cx: Double, cy: Double, cz: Double,
  minX: Double, minY: Double, sizeX: Double, sizeY: Double,
): RenderSurface {
  val count = mesh.vertexCount
  val positions = FloatArray(count * 3)
  val uvs = FloatArray(count * 2)
  for (i in 0 until count) {
    val x = mesh.coords[i * 3]
    val y = mesh.coords[i * 3 + 1]
    val z = mesh.coords[i * 3 + 2]
    positions[i * 3] = (x - cx).toFloat()
    positions[i * 3 + 1] = (z - cz).toFloat()
    positions[i * 3 + 2] = (cy - y).toFloat()
    // планарная развёртка: Filament строит касательные по UV
    uvs[i * 2] = ((x - minX) / sizeX).toFloat()
    uvs[i * 2 + 1] = ((y - minY) / sizeY).toFloat()
  }

  val indices = mesh.triangles.copyOf()
  val normals = FloatArray(count * 3)
  orientUpAndAccumulateNormals(positions, indices, normals)
  normalize(normals)

  // рёбра — по исходным треугольникам: маска рёбер записана в их порядке вершин
  return RenderSurface(
    mesh.layer, positions, normals, uvs, indices, uniqueEdges(mesh.triangles, mesh.edgeMask),
    triangleObjects = mesh.triangleObjects, objectId = mesh.objectId, structure = mesh.structure,
    // у замкнутой сетки (куб из 3DFACE) границы нет: пустую обводку не рисуем — пустой буфер Filament на web роняет экран
    outline = if (mesh.outline) boundaryEdges(mesh.triangles).takeIf { it.isNotEmpty() } else null,
  )
}

/**
 * Треугольники TIN в DXF ориентированы как попало. Разворачиваем каждый нормалью вверх (+Y),
 * чтобы лицевая сторона смотрела на камеру сверху, и копим площадно-взвешенные нормали вершин.
 */
private fun orientUpAndAccumulateNormals(positions: FloatArray, indices: IntArray, normals: FloatArray) {
  for (t in 0 until indices.size / 3) {
    val a = indices[t * 3]
    var b = indices[t * 3 + 1]
    var c = indices[t * 3 + 2]

    val abx = positions[b * 3] - positions[a * 3]
    val aby = positions[b * 3 + 1] - positions[a * 3 + 1]
    val abz = positions[b * 3 + 2] - positions[a * 3 + 2]
    val acx = positions[c * 3] - positions[a * 3]
    val acy = positions[c * 3 + 1] - positions[a * 3 + 1]
    val acz = positions[c * 3 + 2] - positions[a * 3 + 2]

    var nx = aby * acz - abz * acy
    var ny = abz * acx - abx * acz
    var nz = abx * acy - aby * acx
    if (ny < 0f) {
      indices[t * 3 + 1] = c
      indices[t * 3 + 2] = b
      val swap = b
      b = c
      c = swap
      nx = -nx
      ny = -ny
      nz = -nz
    }

    normals.add(a, nx, ny, nz)
    normals.add(b, nx, ny, nz)
    normals.add(c, nx, ny, nz)
  }
}

private fun FloatArray.add(vertex: Int, x: Float, y: Float, z: Float) {
  this[vertex * 3] += x
  this[vertex * 3 + 1] += y
  this[vertex * 3 + 2] += z
}

private fun normalize(normals: FloatArray) {
  for (v in 0 until normals.size / 3) {
    val x = normals[v * 3]
    val y = normals[v * 3 + 1]
    val z = normals[v * 3 + 2]
    val length = sqrt(x * x + y * y + z * z)
    if (length < 1e-12f) {
      normals[v * 3] = 0f
      normals[v * 3 + 1] = 1f
      normals[v * 3 + 2] = 0f
      continue
    }
    normals[v * 3] = x / length
    normals[v * 3 + 1] = y / length
    normals[v * 3 + 2] = z / length
  }
}

/**
 * Рёбра одного треугольника (пары индексов) — внешняя граница поверхности и края дыр; маска рёбер не учитывается:
 * край виден всегда. Ключи рёбер сортируются, берутся встреченные один раз.
 */
internal fun boundaryEdges(indices: IntArray): IntArray {
  val keys = LongArray(indices.size)
  for (t in 0 until indices.size / 3) {
    val a = indices[t * 3]
    val b = indices[t * 3 + 1]
    val c = indices[t * 3 + 2]
    keys[t * 3] = edgeKey(a, b)
    keys[t * 3 + 1] = edgeKey(b, c)
    keys[t * 3 + 2] = edgeKey(c, a)
  }
  keys.sort()
  val edges = IntArray(keys.size * 2)
  var count = 0
  var i = 0
  while (i < keys.size) {
    var j = i + 1
    while (j < keys.size && keys[j] == keys[i]) j++
    if (j - i == 1) {
      edges[count++] = (keys[i] ushr 32).toInt()
      edges[count++] = keys[i].toInt()
    }
    i = j
  }
  return edges.copyOf(count)
}

// обводка поверхности ([RenderSurface.outline]): тёмная, 1,5 dp (в эталоне 1,4 px)
const val OUTLINE_RGB = DxfColors.INK
const val OUTLINE_WIDTH_DP = 1.5f

// общие рёбра соседних треугольников рисуются один раз: ключ ребра (min shl 32 | max), сортировка без объектов;
// скрытые по [edgeMask] рёбра не берутся (ребро видно, если видно хоть в одном треугольнике)
private fun uniqueEdges(indices: IntArray, edgeMask: ByteArray? = null): IntArray {
  var keys = LongArray(indices.size)
  var size = 0
  for (t in 0 until indices.size / 3) {
    val a = indices[t * 3]
    val b = indices[t * 3 + 1]
    val c = indices[t * 3 + 2]
    val mask = if (edgeMask == null) MeshBuilder.ALL_EDGES else edgeMask[t].toInt()
    if (mask and 1 != 0) keys[size++] = edgeKey(a, b)
    if (mask and 2 != 0) keys[size++] = edgeKey(b, c)
    if (mask and 4 != 0) keys[size++] = edgeKey(c, a)
  }
  if (size < keys.size) keys = keys.copyOf(size)
  keys.sort()

  val edges = IntArray(keys.size * 2)
  var count = 0
  for (i in keys.indices) {
    if (i > 0 && keys[i] == keys[i - 1]) continue
    edges[count++] = (keys[i] ushr 32).toInt()
    edges[count++] = keys[i].toInt()
  }
  return edges.copyOf(count)
}

private fun edgeKey(a: Int, b: Int): Long {
  val min = minOf(a, b).toLong()
  val max = maxOf(a, b).toLong()
  return (min shl 32) or max
}

/**
 * Пакет штрихами [on] через промежутки [off]. Фаза идёт вдоль ломаной: отрезок, начатый в конце предыдущего,
 * продолжает её (посадка на поверхность дробит линию по рёбрам треугольников). Штрихов больше [MAX_DASHES] — как есть.
 */
internal fun dashedBatch(batch: LineBatch, on: Double, off: Double): LineBatch {
  val c = batch.coords
  val n = c.size / 6
  val period = on + off
  if (!(on > 0) || !(off > 0)) return batch
  var total = 0.0
  for (k in 0 until n) total += segmentLength(c, k * 6)
  if (total / period + n > MAX_DASHES) return batch
  val out = GrowingDoubleArray()
  val ids = batch.objectIds
  val outIds = if (ids != null) GrowingIntArray() else null
  var phase = 0.0
  for (k in 0 until n) {
    val at = k * 6
    if (k == 0 || c[at] != c[at - 3] || c[at + 1] != c[at - 2] || c[at + 2] != c[at - 1]) phase = 0.0
    val len = segmentLength(c, at)
    var t = 0.0
    while (t < len) {
      val next = minOf(len, t + (if (phase < on) on else period) - phase)
      // остаток фазы меньше погрешности t: шаг не сдвинулся бы — переход к следующей части периода
      if (next <= t) {
        phase = if (phase < on) on else 0.0
        continue
      }
      if (phase < on) {
        for (f in doubleArrayOf(t / len, next / len)) for (axis in 0 until 3) out.add(c[at + axis] + (c[at + 3 + axis] - c[at + axis]) * f)
        outIds?.add(ids!![k])
      }
      phase += next - t
      if (phase >= period) phase = 0.0
      t = next
    }
  }
  return LineBatch(
    batch.layer, batch.color, batch.flat, out.toArray(), batch.lineWeight, batch.extrusion, batch.unbounded, outIds?.toArray(),
  )
}

/**
 * Ставит [SceneObject.planLength] объектам пунктирного [batch] по исходным отрезкам (x, y — план), если она ещё
 * не посчитана: сцену собирают заново (смена листа, X↔Y) — длина та же, второй раз не копится.
 */
private fun accumulatePlanLength(batch: LineBatch, objects: List<SceneObject>) {
  val ids = batch.objectIds ?: return
  val c = batch.coords
  val lengths = HashMap<Int, Double>()
  for (k in 0 until c.size / 6) {
    val at = k * 6
    val dx = c[at + 3] - c[at]
    val dy = c[at + 4] - c[at + 1]
    lengths[ids[k]] = (lengths[ids[k]] ?: 0.0) + sqrt(dx * dx + dy * dy)
  }
  for ((id, length) in lengths) {
    val obj = objects.getOrNull(id) ?: continue
    if (obj.planLength.isNaN()) obj.planLength = length
  }
}

private fun segmentLength(c: DoubleArray, at: Int): Double {
  val dx = c[at + 3] - c[at]
  val dy = c[at + 4] - c[at + 1]
  val dz = c[at + 5] - c[at + 2]
  return sqrt(dx * dx + dy * dy + dz * dz)
}

// штрих и промежуток пунктира — доли размера сцены; предел штрихов на пакет
const val DASH = 0.015
const val GAP = 0.0075
private const val MAX_DASHES = 2_000_000

// отрезки пакета — подряд идущие пары точек; [flatLevel] — отметка для плоского чертежа (иначе z + [lift])
private fun buildLines(batch: LineBatch, flatLevel: Double?, lift: Double, cx: Double, cy: Double, cz: Double): RenderLines {
  val coords = batch.coords
  val pointCount = coords.size / 3
  val positions = FloatArray(pointCount * 3)
  for (i in 0 until pointCount) {
    positions[i * 3] = (coords[i * 3] - cx).toFloat()
    positions[i * 3 + 1] = ((flatLevel ?: (coords[i * 3 + 2] + lift)) - cz).toFloat()
    positions[i * 3 + 2] = (cy - coords[i * 3 + 1]).toFloat()
  }
  return RenderLines(
    batch.layer, batch.color, positions, IntArray(pointCount) { it }, lineWidthPx(batch.lineWeight),
    pointThickness = batch.extrusion == EXTRUSION_POINT,
    objectIds = batch.objectIds,
  )
}

// треугольники заливки — подряд идущие тройки точек, вершины не склеиваются; [step] — ступень по номеру заливки
private fun buildFills(
  batch: FillBatch, flatLevel: Double?, lift: Double, cx: Double, cy: Double, cz: Double, step: (Int) -> Float,
): RenderFills {
  val coords = batch.coords
  val pointCount = coords.size / 3
  val positions = FloatArray(pointCount * 3)
  for (i in 0 until pointCount) {
    positions[i * 3] = (coords[i * 3] - cx).toFloat()
    positions[i * 3 + 1] = ((flatLevel ?: (coords[i * 3 + 2] + lift)) - cz).toFloat()
    positions[i * 3 + 2] = (cy - coords[i * 3 + 1]).toFloat()
  }
  val orders = batch.orders
  val steps = FloatArray(pointCount) { if (orders == null) 0f else step(orders[it / 3]) }
  return RenderFills(batch.layer, batch.color, positions, IntArray(pointCount) { it }, batch.colors, steps, batch.alpha, batch.objectIds)
}

private class Bounds {
  var minX = Double.POSITIVE_INFINITY
  var minY = Double.POSITIVE_INFINITY
  var minZ = Double.POSITIVE_INFINITY
  var maxX = Double.NEGATIVE_INFINITY
  var maxY = Double.NEGATIVE_INFINITY
  var maxZ = Double.NEGATIVE_INFINITY

  val isEmpty: Boolean get() = minX > maxX

  fun add(mesh: SceneMesh) {
    if (mesh.vertexCount == 0) return
    minX = minOf(minX, mesh.minX)
    minY = minOf(minY, mesh.minY)
    minZ = minOf(minZ, mesh.minZ)
    maxX = maxOf(maxX, mesh.maxX)
    maxY = maxOf(maxY, mesh.maxY)
    maxZ = maxOf(maxZ, mesh.maxZ)
  }

  fun add(coords: DoubleArray, zOverride: Double?, lift: Double = 0.0) {
    for (i in 0 until coords.size / 3) add(coords[i * 3], coords[i * 3 + 1], zOverride ?: (coords[i * 3 + 2] + lift))
  }

  fun add(x: Double, y: Double, z: Double) {
    minX = minOf(minX, x)
    minY = minOf(minY, y)
    minZ = minOf(minZ, z)
    maxX = maxOf(maxX, x)
    maxY = maxOf(maxY, y)
    maxZ = maxOf(maxZ, z)
  }
}
