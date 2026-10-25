package dev.iskandern.kotin.scene

import kotlin.math.roundToInt

/**
 * Пикселей на миллиметр толщины линии DXF; результат ограничен [MIN_LINE_WIDTH_PX]…[MAX_LINE_WIDTH_PX].
 * Толщина в DXF не больше 2,11 мм, при 1 px/мм все линии остались бы тонкими.
 */
private const val PX_PER_MM = 3f
const val MIN_LINE_WIDTH_PX = 1f
const val MAX_LINE_WIDTH_PX = 8f

/** Ширина линии на экране, px, из толщины DXF в 1/100 мм (0 — по умолчанию); с шагом в полпикселя, чтобы пакеты не дробились. */
fun lineWidthPx(lineWeight: Int): Float {
  if (lineWeight <= 0) return MIN_LINE_WIDTH_PX
  val px = lineWeight / 100f * PX_PER_MM
  return ((px * 2).roundToInt() / 2f).coerceIn(MIN_LINE_WIDTH_PX, MAX_LINE_WIDTH_PX)
}

/**
 * Отрезки как ленты из квадов: на отрезок четыре вершины A+, A−, B+, B−. [deltas] — по четыре числа на вершину:
 * вектор к другому концу отрезка и сторона: ±1 на конце A, ±2 на конце B (шейдер [RIBBON_VERTEX] раздвигает ленту в
 * пикселях, а по модулю отличает концы — торцы круглые). На другом конце знак стороны обратный — направление там
 * обратное, физическая сторона та же.
 */
class RibbonGeometry(val positions: FloatArray, val deltas: FloatArray, val indices: IntArray)

/** [positions] и [indices] — как у [RenderLines]: пары индексов — отрезки. */
fun buildRibbon(positions: FloatArray, indices: IntArray): RibbonGeometry {
  val segments = indices.size / 2
  val ribbonPositions = FloatArray(segments * 4 * 3)
  val deltas = FloatArray(segments * 4 * 4)
  val ribbonIndices = IntArray(segments * 6)
  for (s in 0 until segments) {
    val a = indices[s * 2] * 3
    val b = indices[s * 2 + 1] * 3
    val dx = positions[b] - positions[a]
    val dy = positions[b + 1] - positions[a + 1]
    val dz = positions[b + 2] - positions[a + 2]
    for (corner in 0 until 4) {
      val atA = corner < 2
      val from = if (atA) a else b
      val v = s * 4 + corner
      ribbonPositions[v * 3] = positions[from]
      ribbonPositions[v * 3 + 1] = positions[from + 1]
      ribbonPositions[v * 3 + 2] = positions[from + 2]
      val sign = if (atA) 1f else -1f
      deltas[v * 4] = dx * sign
      deltas[v * 4 + 1] = dy * sign
      deltas[v * 4 + 2] = dz * sign
      // углы: 0 = A+, 1 = A−, 2 = B+, 3 = B−; на конце B знак обратный (направление к другому концу обратное), модуль 2
      deltas[v * 4 + 3] = (if (corner == 0 || corner == 3) 1f else -1f) * (if (atA) 1f else 2f)
    }
    val v = s * 4
    // квад A+, B+, B−, A−
    ribbonIndices[s * 6] = v
    ribbonIndices[s * 6 + 1] = v + 2
    ribbonIndices[s * 6 + 2] = v + 3
    ribbonIndices[s * 6 + 3] = v
    ribbonIndices[s * 6 + 4] = v + 3
    ribbonIndices[s * 6 + 5] = v + 1
  }
  return RibbonGeometry(ribbonPositions, deltas, ribbonIndices)
}
