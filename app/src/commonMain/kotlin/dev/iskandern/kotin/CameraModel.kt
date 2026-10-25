package dev.iskandern.kotin

import dev.iskandern.kotin.scene.DrawingOutline
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Схема управления камерой.
 *
 * [CameraMode.ORBIT]: левая кнопка / один палец — вращение; правая или средняя кнопка, Shift+левая, два пальца —
 * перемещение; колесо и щипок — зум.
 * [CameraMode.MAP] (как в картах): один палец / левая кнопка — перемещение; два пальца — зум по расстоянию между ними
 * и поворот по углу между ними, вместе со сдвигом пальцев; если жест начат двумя пальцами — вертикальный сдвиг
 * наклоняет; оставшийся после двух один палец — вращение. Правая кнопка / Shift+левая — вращение и наклон.
 */
enum class CameraMode { ORBIT, MAP }

/**
 * Поза камеры: точка интереса pivot, расстояние до неё, азимут и угол места. Не знает ни Filament, ни Compose, поэтому
 * лежит во ViewModel и переживает пересоздание экрана; `CameraState` рендера восстанавливается из неё.
 */
data class CameraPose(
  val pivotX: Float,
  val pivotY: Float,
  val pivotZ: Float,
  val distance: Float,
  val yaw: Float,
  val pitch: Float,
) {
  companion object {
    /** Поза камеры, стоящей в ([eyeX], [eyeY], [eyeZ]) и смотрящей на pivot. */
    fun lookingAt(pivotX: Float, pivotY: Float, pivotZ: Float, eyeX: Float, eyeY: Float, eyeZ: Float): CameraPose {
      val dx = eyeX - pivotX
      val dy = eyeY - pivotY
      val dz = eyeZ - pivotZ
      val distance = sqrt(dx * dx + dy * dy + dz * dz)
      return CameraPose(pivotX, pivotY, pivotZ, distance, yaw = atan2(dx, dz), pitch = asin((dy / distance).coerceIn(-1f, 1f)))
    }
  }
}

/**
 * Луч через экранную точку ([px], [py]) вида [viewWidth] × [viewHeight] и оси камеры. Формулы те же, что у
 * `SceneCameraController`: глаз — pivot + направление на камеру × расстояние, угол обзора [fovDegrees] — по вертикали.
 */
fun CameraPose.pickRay(fovDegrees: Double, px: Float, py: Float, viewWidth: Float, viewHeight: Float): PickRay {
  val tanHalf = tan(fovDegrees * PI / 360)
  val nx = (px / viewWidth * 2.0 - 1.0) * (viewWidth / viewHeight) * tanHalf
  val ny = (1.0 - py / viewHeight * 2.0) * tanHalf
  val b = cameraBasis()
  var dx = -b[0] + b[3] * nx + b[6] * ny
  var dy = -b[1] + b[4] * nx + b[7] * ny
  var dz = -b[2] + b[5] * nx + b[8] * ny
  val length = sqrt(dx * dx + dy * dy + dz * dz)
  dx /= length; dy /= length; dz /= length
  return PickRay(
    pivotX + b[0] * distance, pivotY + b[1] * distance, pivotZ + b[2] * distance,
    dx, dy, dz,
    b[3], b[4], b[5], b[6], b[7], b[8], b[0], b[1], b[2],
    2.0 * tanHalf / viewHeight,
  )
}

/**
 * Экранная точка (x, y) для точки сцены ([x], [y], [z]) — обратное к [pickRay]; null — точка за камерой.
 * [y] уже с вертикальным масштабом.
 */
fun CameraPose.project(fovDegrees: Double, x: Float, y: Float, z: Float, viewWidth: Float, viewHeight: Float): Pair<Float, Float>? {
  val tanHalf = tan(fovDegrees * PI / 360)
  val b = cameraBasis()
  val rx = x - (pivotX + b[0] * distance)
  val ry = y - (pivotY + b[1] * distance)
  val rz = z - (pivotZ + b[2] * distance)
  val depth = -(rx * b[0] + ry * b[1] + rz * b[2])
  if (depth <= 1e-6) return null
  val nx = (rx * b[3] + ry * b[4] + rz * b[5]) / (depth * tanHalf * (viewWidth / viewHeight))
  val ny = (rx * b[6] + ry * b[7] + rz * b[8]) / (depth * tanHalf)
  return Pair(((nx + 1.0) / 2.0 * viewWidth).toFloat(), ((1.0 - ny) / 2.0 * viewHeight).toFloat())
}

/**
 * Чертёж целиком вне кадра: [dirX], [dirY] — единичное направление на него от центра кадра в экранных осях (y вниз),
 * [distance] — от точки интереса до контура чертежа в плане, 0 — точка интереса над ним.
 */
class OffscreenDrawing(val dirX: Float, val dirY: Float, val distance: Float)

/**
 * Рабочие буферы [offscreenDrawing] и [drawingDirection]: расчёт идёт на каждом кадре движения камеры, и без них
 * кадр оставлял бы около 2,5 КБ мусора. Один экземпляр — на один поток (камера считает в UI-потоке).
 */
class DrawingPointerSpace {
  internal val basis = DoubleArray(9)
  internal val center = DoubleArray(2)
  internal var cam = DoubleArray(0)
  internal var seg = DoubleArray(0)
}

/** [offscreenDrawing] со своими буферами — для разовых вызовов и тестов. */
fun CameraPose.offscreenDrawing(
  fovDegrees: Double, outline: DrawingOutline, verticalScale: Float, viewWidth: Float, viewHeight: Float,
): OffscreenDrawing? = offscreenDrawing(fovDegrees, outline, verticalScale, viewWidth, viewHeight, DrawingPointerSpace())

/**
 * Чертёж с контуром [outline] (по высоте — с вертикальным масштабом [verticalScale]) целиком вне кадра
 * [viewWidth] × [viewHeight] — куда на него смотреть; null — хоть часть контура в кадре. Рёбра призмы контура
 * обрезаются плоскостью у глаза и проецируются на экран; проекция выпуклой призмы отделена от кадра, если её
 * отделяет ось кадра или нормаль одного из рёбер. Часть за камерой на экран не попадает и в кадр чертёж не
 * возвращает. Призма — 16 × 2 вершины, 48 рёбер: несколько тысяч умножений на кадр движения камеры, без сортировки.
 * [space] — буферы, которые переживают кадр.
 */
fun CameraPose.offscreenDrawing(
  fovDegrees: Double, outline: DrawingOutline, verticalScale: Float, viewWidth: Float, viewHeight: Float, space: DrawingPointerSpace,
): OffscreenDrawing? {
  if (viewWidth <= 0f || viewHeight <= 0f || outline.size == 0) return null
  val ty = tan(fovDegrees * PI / 360)
  val tx = ty * viewWidth / viewHeight
  val b = cameraBasis(space.basis)
  val eyeX = pivotX + b[0] * distance
  val eyeY = pivotY + b[1] * distance
  val eyeZ = pivotZ + b[2] * distance
  val n = outline.size
  val bottom = outline.minY * verticalScale
  val top = outline.maxY * verticalScale
  // вершины призмы в осях камеры (вправо, вверх, глубина вперёд): сначала низ, потом верх
  if (space.cam.size < n * 2 * 3) space.cam = DoubleArray(n * 2 * 3)
  val cam = space.cam
  for (k in 0 until n * 2) {
    val rx = outline.hull[(k % n) * 2] - eyeX
    val ry = (if (k < n) bottom else top) - eyeY
    val rz = outline.hull[(k % n) * 2 + 1] - eyeZ
    cam[k * 3] = rx * b[3] + ry * b[4] + rz * b[5]
    cam[k * 3 + 1] = rx * b[6] + ry * b[7] + rz * b[8]
    cam[k * 3 + 2] = -(rx * b[0] + ry * b[1] + rz * b[2])
  }
  // рёбра на экране в координатах кадра −1..1: низ, верх, вертикали; ближе near точка уходит в бесконечность
  val near = max(distance * 1e-4, 1e-6)
  if (space.seg.size < n * 3 * 4) space.seg = DoubleArray(n * 3 * 4)
  val seg = space.seg
  var count = 0
  fun edge(i: Int, j: Int) {
    var di = cam[i * 3 + 2]
    var dj = cam[j * 3 + 2]
    if (di < near && dj < near) return
    var xi = cam[i * 3]; var yi = cam[i * 3 + 1]
    var xj = cam[j * 3]; var yj = cam[j * 3 + 1]
    if (di < near) {
      val t = (near - di) / (dj - di)
      xi += (xj - xi) * t; yi += (yj - yi) * t; di = near
    } else if (dj < near) {
      val t = (near - dj) / (di - dj)
      xj += (xi - xj) * t; yj += (yi - yj) * t; dj = near
    }
    seg[count * 4] = xi / (di * tx)
    seg[count * 4 + 1] = yi / (di * ty)
    seg[count * 4 + 2] = xj / (dj * tx)
    seg[count * 4 + 3] = yj / (dj * ty)
    count++
  }
  for (i in 0 until n) {
    val j = (i + 1) % n
    edge(i, j)
    edge(n + i, n + j)
    edge(i, n + i)
  }
  if (count > 0 && !separatedFromFrame(seg, count)) return null
  return drawingDirection(outline, verticalScale, space)
}

/** [drawingDirection] со своими буферами — для разовых вызовов и тестов. */
fun CameraPose.drawingDirection(outline: DrawingOutline, verticalScale: Float): OffscreenDrawing =
  drawingDirection(outline, verticalScale, DrawingPointerSpace())

/**
 * Куда на экране смотреть на чертёж [outline] и как он далеко — без проверки, в кадре ли он: несколько десятков
 * умножений, поэтому указатель может ехать за камерой каждый кадр, даже когда проверка [offscreenDrawing] реже.
 * [space] — буферы, которые переживают кадр.
 */
fun CameraPose.drawingDirection(outline: DrawingOutline, verticalScale: Float, space: DrawingPointerSpace): OffscreenDrawing {
  val b = cameraBasis(space.basis)
  // направление на середину контура в осях камеры: на экране оно то же при любом отношении сторон, а у середины за
  // камерой показывает, куда повернуть
  val center = outline.center(space.center)
  val cx = center[0] - (pivotX + b[0] * distance)
  val cy = (outline.minY + outline.maxY) / 2.0 * verticalScale - (pivotY + b[1] * distance)
  val cz = center[1] - (pivotZ + b[2] * distance)
  var dx = cx * b[3] + cy * b[4] + cz * b[5]
  var dy = -(cx * b[6] + cy * b[7] + cz * b[8])
  val length = sqrt(dx * dx + dy * dy)
  if (length < 1e-9) {
    // ровно впереди быть не может (он бы в кадре), значит позади: вниз — к земле перед камерой
    dx = 0.0; dy = 1.0
  } else {
    dx /= length; dy /= length
  }
  return OffscreenDrawing(dx.toFloat(), dy.toFloat(), outline.planDistance(pivotX, pivotZ))
}

/**
 * Поза, в которой ближайшая часть чертежа в кадре: точка интереса переезжает на точку геометрии [nearest]
 * (x, y, z сцены, высота без вертикального масштаба) и ещё немного внутрь, к середине контура [outline], — чтобы
 * в кадре был не только край. Сдвиг внутрь не больше [INWARD_SHIFT] половины высоты кадра: точка остаётся на экране.
 * Расстояние (зум), азимут и наклон прежние — переход к позе без поворота и без смены масштаба.
 */
fun CameraPose.towardDrawing(fovDegrees: Double, nearest: FloatArray, outline: DrawingOutline, verticalScale: Float): CameraPose {
  val center = outline.center(DoubleArray(2))
  val vx = center[0] - nearest[0]
  val vz = center[1] - nearest[2]
  val length = sqrt(vx * vx + vz * vz)
  val shift = min(length, distance * tan(fovDegrees * PI / 360) * INWARD_SHIFT)
  val k = if (length > 1e-9) shift / length else 0.0
  return copy(pivotX = (nearest[0] + vx * k).toFloat(), pivotY = nearest[1] * verticalScale, pivotZ = (nearest[2] + vz * k).toFloat())
}

/** Сдвиг от ближайшей точки чертежа внутрь — доля половины высоты кадра. */
private const val INWARD_SHIFT = 0.4

/**
 * Отрезки [seg] (по четыре числа: x1, y1, x2, y2) — рёбра проекции выпуклого тела — не задевают кадр −1..1 × −1..1:
 * разделяющая ось — ось кадра или нормаль одного из отрезков (среди них все рёбра силуэта).
 */
private fun separatedFromFrame(seg: DoubleArray, count: Int): Boolean {
  fun separatedAlong(nx: Double, ny: Double): Boolean {
    var lo = Double.POSITIVE_INFINITY
    var hi = Double.NEGATIVE_INFINITY
    for (k in 0 until count * 2) {
      val p = seg[k * 2] * nx + seg[k * 2 + 1] * ny
      if (p < lo) lo = p
      if (p > hi) hi = p
    }
    val half = abs(nx) + abs(ny)
    return lo > half || hi < -half
  }
  if (separatedAlong(1.0, 0.0) || separatedAlong(0.0, 1.0)) return true
  for (k in 0 until count) {
    val dx = seg[k * 4 + 2] - seg[k * 4]
    val dy = seg[k * 4 + 3] - seg[k * 4 + 1]
    if ((dx != 0.0 || dy != 0.0) && separatedAlong(-dy, dx)) return true
  }
  return false
}

// середина контура в плане (x, z) — центр его прямоугольника, в [out]
private fun DrawingOutline.center(out: DoubleArray): DoubleArray {
  var minX = Double.POSITIVE_INFINITY
  var maxX = Double.NEGATIVE_INFINITY
  var minZ = Double.POSITIVE_INFINITY
  var maxZ = Double.NEGATIVE_INFINITY
  for (i in 0 until size) {
    minX = min(minX, hull[i * 2].toDouble()); maxX = max(maxX, hull[i * 2].toDouble())
    minZ = min(minZ, hull[i * 2 + 1].toDouble()); maxZ = max(maxZ, hull[i * 2 + 1].toDouble())
  }
  out[0] = (minX + maxX) / 2
  out[1] = (minZ + maxZ) / 2
  return out
}

// расстояние в плане от точки до контура, 0 — точка внутри. Контур без площади (точка, отрезок — у далёкого объекта
// за кадром) внутри себя ничего не держит: иначе нулевые рёбра признали бы любую точку внутренней
private fun DrawingOutline.planDistance(x: Float, z: Float): Float {
  if (size == 1) return sqrt((x - hull[0]) * (x - hull[0]) + (z - hull[1]) * (z - hull[1]))
  var inside = size >= 3
  var best = Float.POSITIVE_INFINITY
  var area = 0f
  var perimeter = 0f
  for (i in 0 until size) {
    val j = (i + 1) % size
    val ax = hull[i * 2]; val az = hull[i * 2 + 1]
    val ex = hull[j * 2] - ax; val ez = hull[j * 2 + 1] - az
    area += ax * ez - az * ex
    perimeter += sqrt(ex * ex + ez * ez)
    // контур против часовой стрелки в осях (x, z): снаружи — справа от ребра
    if (ex * (z - az) - ez * (x - ax) < 0f) inside = false
    val len2 = ex * ex + ez * ez
    val t = if (len2 > 0f) (((x - ax) * ex + (z - az) * ez) / len2).coerceIn(0f, 1f) else 0f
    val px = ax + ex * t - x
    val pz = az + ez * t - z
    best = min(best, sqrt(px * px + pz * pz))
  }
  return if (inside && abs(area) > perimeter * perimeter * DEGENERATE_AREA) 0f else best
}

// удвоенная площадь контура меньше этой доли квадрата периметра — контур вырожден
private const val DEGENERATE_AREA = 1e-6f


// от pivot к камере (e), вправо (r) и вверх (u = e × r) — девять чисел подряд, в [out]
private fun CameraPose.cameraBasis(out: DoubleArray = DoubleArray(9)): DoubleArray {
  val ex = cos(pitch.toDouble()) * sin(yaw.toDouble())
  val ey = sin(pitch.toDouble())
  val ez = cos(pitch.toDouble()) * cos(yaw.toDouble())
  val rx = cos(yaw.toDouble())
  val rz = -sin(yaw.toDouble())
  out[0] = ex; out[1] = ey; out[2] = ez
  out[3] = rx; out[4] = 0.0; out[5] = rz
  out[6] = ey * rz; out[7] = ez * rx - ex * rz; out[8] = -ey * rx
  return out
}

/** Что просят сделать с камерой: кнопка интерфейса или внешнее событие. Выполняет [SceneCameraController.execute]. */
sealed interface CameraCommand {
  /** Вид, с которым открыта сцена. */
  data object Home : CameraCommand

  /** Север вверх (у плоского чертежа — и строго сверху). */
  data object North : CameraCommand

  /** Строго сверху над ([x], [z]) сцены, севером вверх: по вертикали в кадр помещается [height] единиц. */
  data class ShowTop(val x: Float, val z: Float, val height: Float) : CameraCommand

  /** Зум к центру кадра: расстояние × [factor] (<1 — приблизить). Кнопки «− +». */
  data class Zoom(val factor: Float) : CameraCommand

  /** Плавный переход в позу [pose] — указатель подводит ушедший чертёж в кадр, не поворачивая камеру и не меняя зум. */
  data class FlyTo(val pose: CameraPose) : CameraCommand
}

/**
 * Состояние камеры на экране: только то, что меняет интерфейс. Поза сюда намеренно не входит — её запись при каждой
 * остановке камеры перекомпоновывала бы весь экран; она лежит в [CameraPoseMemory].
 */
data class CameraUiState(
  val mode: CameraMode = CameraMode.MAP,
  /** Ждущая исполнения команда: UI выполняет её и подтверждает [consumed], чтобы после пересоздания она не повторилась. */
  val command: CameraCommand? = null,
) {
  fun withMode(mode: CameraMode) = copy(mode = mode)

  fun send(command: CameraCommand) = copy(command = command)

  fun consumed(command: CameraCommand) = if (this.command == command) copy(command = null) else this

  /** Сцена сменилась: команда прежней сцены к новой не относится. */
  fun forNewScene() = copy(command = null)
}

/**
 * Вид одного файла, которому принадлежит своя поза камеры: у модели и каждого листа сцена своя, а смена осей или
 * «чертёж в плоскость» меняют координаты, так что поза от другого вида не подойдёт.
 */
data class CameraViewKey(val sheet: Int, val swapXY: Boolean, val flattened: Boolean)

/**
 * Позы, на которых камера остановилась, по видам одного файла: вернулись на лист или пересоздали экран — камера там,
 * где её оставили. Записи от чужого владельца (прежнего файла, экран которого ещё закрывается) отбрасываются.
 */
class CameraPoseMemory {
  private var owner: Any? = null
  private val poses = HashMap<CameraViewKey, CameraPose>()

  /** Открыт новый файл: прежние позы забываются, записывать может только [owner]. */
  fun reset(owner: Any) {
    this.owner = owner
    poses.clear()
  }

  fun remember(owner: Any, view: CameraViewKey, pose: CameraPose) {
    if (owner === this.owner) poses[view] = pose
  }

  fun recall(view: CameraViewKey): CameraPose? = poses[view]
}
