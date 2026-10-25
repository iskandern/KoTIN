package dev.iskandern.kotin

import dev.iskandern.kotin.scene.OutlierGroup
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

// Расчёты экрана без Compose UI: общие у интерфейса на Compose и web-версии на DOM (:webhtml)

/** Формат файла по расширению — для строки под именем: «LandXML», «DXF». */
internal fun fileFormat(name: String): String = when (val ext = name.substringAfterLast('.', "").lowercase()) {
  "xml" -> "LandXML"
  else -> ext.uppercase()
}

/** Целое с разделителем разрядов — узким неразрывным пробелом: «12 345». */
internal fun groupDigits(n: Long): String {
  val s = n.toString()
  val sign = if (n < 0) "-" else ""
  val digits = s.removePrefix("-")
  return sign + digits.reversed().chunked(3).joinToString(" ").reversed()
}

/**
 * Расстояние для плашки: до 100 м — до метра, до 1 км — до 10 м, дальше — до 100 м; null — ближе метра. Крупный шаг
 * не только читается легче, но и реже пересобирает текст, пока камера едет.
 */
internal fun roundedDistance(meters: Float): Double? {
  if (!(meters >= 1f)) return null
  val step = when {
    meters < 100f -> 1.0
    meters < 1000f -> 10.0
    else -> 100.0
  }
  return kotlin.math.round(meters / step) * step
}

/** Плашка у края: [group] — номер группы далёких объектов, −1 — сам чертёж; [count] — объектов за ней. */
internal data class PointerTarget(val group: Int, val count: Int)

/** Только плашка «Чертёж». */
internal val DRAWING_POINTERS = listOf(PointerTarget(-1, 0))

/**
 * Плашки на группы [groups] далёких объектов, которые [offscreen] нашёл вне кадра: ближние первыми, не больше
 * [MAX_OUTLIER_POINTERS]. Группа почти в том же направлении (угол меньше [mergeAngle], радианы), что уже взятая
 * ближняя, отдельной плашки не получает — её объекты прибавляются к ближней.
 */
internal fun outlierPointers(
  groups: List<OutlierGroup>, mergeAngle: Float, offscreen: (OutlierGroup) -> OffscreenDrawing?,
): List<PointerTarget> {
  val found = groups.indices.mapNotNull { i -> offscreen(groups[i])?.let { i to it } }.sortedBy { it.second.distance }
  val result = ArrayList<PointerTarget>()
  val directions = ArrayList<OffscreenDrawing>()
  for ((i, d) in found) {
    val near = directions.indexOfFirst { acos((it.dirX * d.dirX + it.dirY * d.dirY).coerceIn(-1f, 1f)) < mergeAngle }
    if (near >= 0) {
      result[near] = result[near].copy(count = result[near].count + groups[i].count)
    } else if (result.size < MAX_OUTLIER_POINTERS) {
      result += PointerTarget(i, groups[i].count)
      directions += d
    }
  }
  return result
}

/**
 * Плашки у края: «Чертёж», если [drawingOffscreen]; иначе плашки на далёкие объекты — только при [showOutliers]
 * (выключено — [offscreen] не зовётся).
 */
internal fun pointerTargets(
  drawingOffscreen: Boolean, showOutliers: Boolean, groups: List<OutlierGroup>, mergeAngle: Float,
  offscreen: (OutlierGroup) -> OffscreenDrawing?,
): List<PointerTarget> = when {
  drawingOffscreen -> DRAWING_POINTERS
  !showOutliers || groups.isEmpty() -> emptyList()
  else -> outlierPointers(groups, mergeAngle, offscreen)
}

private const val MAX_OUTLIER_POINTERS = 3

/**
 * Оси чертежа X, Y (север), Z (вверх) на экране при азимуте [yaw] и угле места [pitch] камеры: для каждой по три
 * числа подряд в [out] — (вправо, вверх по экрану, к зрителю), длина вектора 1. Сцена: X чертежа — X, Y чертежа — −Z,
 * Z чертежа — Y.
 */
internal fun axesOnScreen(yaw: Float, pitch: Float, out: FloatArray = FloatArray(9)): FloatArray {
  val sy = sin(yaw)
  val cy = cos(yaw)
  val sp = sin(pitch)
  val cp = cos(pitch)
  // вправо (cos yaw, 0, −sin yaw), вверх (−sin p·sin y, cos p, −sin p·cos y), к камере (cos p·sin y, sin p, cos p·cos y)
  out[0] = cy; out[1] = -sp * sy; out[2] = cp * sy
  out[3] = sy; out[4] = sp * cy; out[5] = -cp * cy
  out[6] = 0f; out[7] = cp; out[8] = sp
  return out
}

/** Шаг кнопок «− +»: во сколько раз меняется расстояние до точки интереса. */
internal const val ZOOM_STEP = 1.25f
