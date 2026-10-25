package dev.iskandern.kotin

import io.github.erkko68.filament.Renderer
import dev.iskandern.kotin.dxf.POINT_MODE_UNSET
import dev.iskandern.kotin.landxml.StationLabels
import dev.iskandern.kotin.scene.FACE_CAP_HEIGHT
import dev.iskandern.kotin.scene.MARKER_DEFAULT_DP
import dev.iskandern.kotin.scene.MARKER_QUAD_PX
import dev.iskandern.kotin.scene.MARKER_SIZE_PX
import dev.iskandern.kotin.scene.MIN_MARKER_DP
import dev.iskandern.kotin.scene.RenderScene
import dev.iskandern.kotin.scene.markerParts
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/** Ступень показа подписей: все, прореженно по масштабу, выключены. */
enum class LabelTier {
  ALL, THIN, OFF;

  /** На ступень легче: все → прореживать → выключено. */
  fun lighter(): LabelTier = if (this == ALL) THIN else OFF
}

/** Настройки с подписями на ступени [tier]. */
fun ViewSettings.withLabelTier(tier: LabelTier) = copy(showLabels = tier != LabelTier.OFF, thinLabels = tier == LabelTier.THIN)

/** Настройки с пикетажем: [show] — виден, [all] — с промежуточными пикетами, иначе только концы трасс. */
fun ViewSettings.withStationing(show: Boolean, all: Boolean): ViewSettings {
  val hidden = hiddenLayers - StationLabels.LAYER - StationLabels.MID_LAYER
  return copy(
    allStations = all,
    hiddenLayers = when {
      !show -> hidden + StationLabels.LAYER + StationLabels.MID_LAYER
      all -> hidden
      else -> hidden + StationLabels.MID_LAYER
    },
  )
}

/** Промежуточные пикеты закрывают исходный вид: доля налезающих ([stationsOverlap]) не меньше [CROWDED_SHARE]. */
fun RenderScene.stationsCrowded(view: Viewport): Boolean = stationsOverlap(view) >= CROWDED_SHARE

/**
 * Доля подписей промежуточных пикетов в кадре [view], налезающих на другие подписи пикетажа; нет таких — 0. Положение —
 * в плане при масштабе исходного вида в его середине ([homeEye]), наклон учтён сжатием по глубине. Прямоугольник
 * подписи — строка; для подписей другой трассы — от точки до верха строки: строка не должна ложиться на чужую засечку,
 * а на свою линию между пикетами — может.
 */
internal fun RenderScene.stationsOverlap(view: Viewport): Float {
  if (view.width <= 0 || view.height <= 0) return 0f
  val eye = homeEye(this, view.width.toFloat() / view.height)
  val tx = home?.x ?: 0f
  val ty = if (isFlatScene(this)) 0f else home?.y ?: 0f
  val tz = home?.z ?: 0f
  val dx = eye.x - tx
  val dy = eye.y - ty
  val dz = eye.z - tz
  val distance = sqrt(dx * dx + dy * dy + dz * dz)
  if (distance <= 0f) return 0f
  val dpPerMeter = view.height / view.density / (2f * distance * tan(FOV_RADIANS / 2))
  // наклон сжимает план по глубине в синус угла взгляда
  val depthScale = abs(dy) / distance
  val halfW = view.width / view.density / 2
  val halfH = view.height / view.density / 2

  // y — точка вставки (низ засечки), y0..y1 — строка; экранный y растёт вниз
  class Box(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val y: Float, val mid: Boolean, val objectId: Int)
  val boxes = ArrayList<Box>()
  for (label in labels) {
    val mid = label.layer == StationLabels.MID_LAYER
    // засечка без строки (промежуточного пикета) места не занимает
    if (!mid && label.layer != StationLabels.LAYER || label.text.isEmpty()) continue
    val x = (label.x - tx) * dpPerMeter
    val y = (label.z - tz) * dpPerMeter * depthScale
    if (abs(x) > halfW || abs(y) > halfH) continue
    // высота подписи пикета < 0 — заглавные в dp; моноширинный символ — 0,6 кегля
    val cap = abs(label.height)
    val width = label.text.length * MONO_ADVANCE * cap / FACE_CAP_HEIGHT[label.face]
    boxes.add(Box(x - width / 2, y - label.liftDp - cap, x + width / 2, y - label.liftDp, y, mid, label.objectId))
  }
  val mids = boxes.count { it.mid }
  if (mids == 0) return 0f
  // сетка с ячейкой не меньше прямоугольника: соседи — в своей и восьми соседних ячейках
  val cell = boxes.maxOf { maxOf(it.x1 - it.x0, it.y - it.y0) }.coerceAtLeast(1f)
  fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)
  val grid = HashMap<Long, ArrayList<Box>>()
  for (b in boxes) grid.getOrPut(key((b.x0 / cell).toInt(), (b.y0 / cell).toInt())) { ArrayList(2) }.add(b)
  var crowded = 0
  for (a in boxes) {
    if (!a.mid) continue
    val cx = (a.x0 / cell).toInt()
    val cy = (a.y0 / cell).toInt()
    var hit = false
    for (i in -1..1) for (j in -1..1) for (b in grid[key(cx + i, cy + j)].orEmpty()) {
      val other = b.objectId != a.objectId
      val aLow = if (other) a.y else a.y1
      val bLow = if (other) b.y else b.y1
      if (b !== a && a.x0 < b.x1 && b.x0 < a.x1 && a.y0 < bLow && b.y0 < aLow) hit = true
    }
    if (hit) crowded++
  }
  return crowded.toFloat() / mids
}

/**
 * Нагрузка подписей сцены: [labels] — число подписей, [textQuads] — квадов символов, [markerQuads] — квадов значков
 * точек; [markerDp] — сторона квада значка, dp.
 */
class LabelLoad(val labels: Int, val textQuads: Int, val markerQuads: Int, val markerDp: Float) {
  val quads: Int get() = textQuads + markerQuads
}

/** Нагрузка подписей и значков на слоях, не скрытых в [hiddenLayers]. */
fun RenderScene.labelLoad(hiddenLayers: Set<String>): LabelLoad {
  var labels = 0
  var chars = 0
  for (label in this.labels) {
    if (label.layer in hiddenLayers) continue
    labels++
    // квад — на символ; пробелы и переносы квадов не дают
    for (ch in label.text) if (!ch.isWhitespace()) chars++
  }
  val parts = markerParts(pointMode).size
  val markers = points.count { it.layer !in hiddenLayers } * parts
  // значок по умолчанию — MARKER_DEFAULT_DP номинальной стороны; мировой размер при отдалении сжимается до MIN_MARKER_DP
  val nominalDp = if (pointMode == POINT_MODE_UNSET || pointSize <= 0f) MARKER_DEFAULT_DP else MIN_MARKER_DP
  return LabelLoad(labels, chars, markers, nominalDp * MARKER_QUAD_PX / MARKER_SIZE_PX)
}

/**
 * Сколько раз подписи и значки перерисовывают экран за кадр в худшем случае — всё в кадре, подписи не меньше
 * [MIN_MARKER_DP]: площадь квадов × проходы (ореол и текст) / площадь вида. Вид [viewWidth] × [viewHeight] px,
 * [density] — px на dp.
 */
fun labelOverdraw(load: LabelLoad, density: Float, viewWidth: Int, viewHeight: Int): Float {
  val area = viewWidth.toFloat() * viewHeight
  if (area <= 0f) return 0f
  val textSide = TEXT_CELL_TO_SIZE * MIN_MARKER_DP * density
  val markerSide = load.markerDp * density
  return (load.textQuads * textSide * textSide + load.markerQuads * markerSide * markerSide) * LABEL_PASSES / area
}

/** Ступень по умолчанию для файла: по перерисовке экрана ([labelOverdraw]) и числу вершин квадов. Подписей нет — все. */
fun labelTier(load: LabelLoad, density: Float, viewWidth: Int, viewHeight: Int): LabelTier {
  if (load.labels == 0) return LabelTier.ALL
  if (load.quads.toLong() * 4 * LABEL_PASSES > MAX_LABEL_VERTICES) return LabelTier.OFF
  val overdraw = labelOverdraw(load, density, viewWidth, viewHeight)
  return when {
    overdraw <= OVERDRAW_ALL -> LabelTier.ALL
    overdraw <= OVERDRAW_THIN -> LabelTier.THIN
    else -> LabelTier.OFF
  }
}

/** Медиана интервалов между кадрами [frameNanos] (времена кадров по порядку), мс; меньше двух кадров — 0. */
fun medianFrameMillis(frameNanos: LongArray): Double {
  if (frameNanos.size < 2) return 0.0
  val intervals = LongArray(frameNanos.size - 1) { frameNanos[it + 1] - frameNanos[it] }.sorted()
  val mid = intervals.size / 2
  val median = if (intervals.size % 2 == 1) intervals[mid].toDouble() else (intervals[mid - 1] + intervals[mid]) / 2.0
  return median / 1_000_000.0
}

/**
 * Время кадра, мс, по кадрам, которые Filament действительно нарисовал. Когда GPU не успевает, Filament пропускает кадр
 * (`beginFrame` = false), а кадры Compose идут как шли — их интервалы этого не видят. [drawnBegins] — начала
 * нарисованных за окно замера кадров (нс, по порядку), [windowMillis] — длина окна: медиана интервалов, кадров меньше
 * двух — всё окно на кадр. Истории кадров нет (null) — [composeMillis], медиана интервалов кадров Compose.
 */
fun drawnFrameMillis(drawnBegins: LongArray?, windowMillis: Double, composeMillis: Double): Double = when {
  drawnBegins == null -> composeMillis
  drawnBegins.size < 2 -> maxOf(windowMillis, composeMillis)
  else -> medianFrameMillis(drawnBegins)
}

/** Номер последнего кадра в истории рендерера; истории нет (платформа её не ведёт) — null. */
fun Renderer.lastDrawnFrame(): Int? = runCatching { getFrameInfoHistory(maxFrameHistorySize).maxOfOrNull { it.frameId } }.getOrNull()

/** Начала кадров (нс), нарисованных после кадра [afterId], по порядку; истории нет — null. */
fun Renderer.drawnFrameBegins(afterId: Int): LongArray? = runCatching {
  getFrameInfoHistory(maxFrameHistorySize).filter { it.frameId > afterId }.sortedBy { it.frameId }.map { it.beginFrame }.toLongArray()
}.getOrNull()

/** Кадры с подписями медленные: медиана интервалов больше полутора кадров при 60 Гц. */
fun framesTooSlow(medianMillis: Double) = medianMillis > SLOW_FRAME_MILLIS

/** Результат замера кадров за запуск: медленное устройство на следующих файлах сразу получает ступень легче. */
object LabelFrames {
  var slow = false
}

// пороги перерисовки (раз экран за кадр) — по замеру на планшете (Adreno 506, 1280×800, орбита): все подписи держат
// 60 FPS до 0,9 (3 тыс. подписей), при 1,2 — уже 48 FPS, 30 FPS — до ~2,2; прореживание держит 60 FPS на всех файлах,
// до 10,7 (35 тыс. подписей), и в приближении. Дальше не мерено: выключены, медленнее — понизит замер кадров
internal const val OVERDRAW_ALL = 1f
internal const val OVERDRAW_THIN = 11f

// доля налезающих подписей промежуточных пикетов, с которой исходный вид показывает только концы трасс
internal const val CROWDED_SHARE = 0.5f

/** Сколько кадров мерить после первого кадра сцены с подписями. */
internal const val FRAME_CHECK_FRAMES = 30

// площадь ячейки атласа (символ с полем SDF ATLAS_PAD) к квадрату кегля — 1,4 по стороне
private const val TEXT_CELL_TO_SIZE = 1.4f
// проходы ореола и текста
private const val LABEL_PASSES = 2
// вершин квадов подписей с обоими проходами, больше — выключены при любой площади
private const val MAX_LABEL_VERTICES = 1_000_000L
// 1,5 кадра при 60 Гц
private const val SLOW_FRAME_MILLIS = 25.0
// ширина символа моноширинного шрифта пикетажа к кеглю
private const val MONO_ADVANCE = 0.6f
private const val FOV_RADIANS = (FOV_DEGREES * PI / 180).toFloat()
