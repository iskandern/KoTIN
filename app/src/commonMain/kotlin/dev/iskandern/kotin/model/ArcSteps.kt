package dev.iskandern.kotin.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.sqrt

/** Лимит отрезков сцены: дальше чтение останавливается и ставит [SceneGeometry.truncated]. */
const val MAX_SCENE_SEGMENTS = 5_000_000

/**
 * Разбиение дуг по допуску от размера сцены, общее для форматов: хорда отходит от дуги не больше
 * размер сцены / [SCENE_TOLERANCE_DIVISOR], но шагов на оборот от [MIN_STEPS_PER_TURN] до [MAX_STEPS_PER_TURN];
 * без размера сцены — [ARC_STEPS_PER_TURN].
 */
object ArcSteps {
  const val ARC_STEPS_PER_TURN = 64
  const val MIN_STEPS_PER_TURN = 16
  const val MAX_STEPS_PER_TURN = 512
  const val SCENE_TOLERANCE_DIVISOR = 10000.0

  /** Шагов на оборот у дуги радиуса [radius] в мировых единицах: от [min] до [max]; без размера сцены и радиуса — [fallback]. */
  fun perTurn(
    radius: Double, sceneSize: Double,
    min: Int = MIN_STEPS_PER_TURN, max: Int = MAX_STEPS_PER_TURN, fallback: Int = ARC_STEPS_PER_TURN,
  ): Int {
    val r = abs(radius)
    if (sceneSize <= 0.0 || r.isNaN() || r.isInfinite()) return fallback
    val tolerance = sceneSize / SCENE_TOLERANCE_DIVISOR
    if (r <= tolerance) return min
    return ceil(PI / acos(1 - tolerance / r)).toInt().coerceIn(min, max)
  }

  /** Шагов дуги с углом [sweep] (радианы): не меньше 2 и не больше [maxSteps]. */
  fun steps(sweep: Double, radius: Double, sceneSize: Double, maxSteps: Int = Int.MAX_VALUE): Int =
    ceil(perTurn(radius, sceneSize) * abs(sweep) / (2 * PI)).toInt().coerceIn(2, maxSteps)

  /** Размер сцены для допуска — диагональ габаритов. */
  fun sceneSize(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): Double {
    val dx = maxX - minX
    val dy = maxY - minY
    val dz = maxZ - minZ
    return sqrt(dx * dx + dy * dy + dz * dz)
  }
}
