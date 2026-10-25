package dev.iskandern.kotin

import dev.iskandern.kotin.scene.CANDIDATE_MARK_DP
import dev.iskandern.kotin.scene.CANDIDATE_SHIFT_DP
import dev.iskandern.kotin.scene.candidateMarkShifts
import kotlin.math.hypot

/**
 * Места попадания кандидатов выбора по три числа — для номеров в сцене (`buildCandidateMarksMesh`). Один кандидат —
 * номеров нет, null.
 */
fun candidateHits(selection: ObjectSelection): FloatArray? {
  val candidates = selection.candidates
  if (candidates.size < 2) return null
  return FloatArray(candidates.size * 3) { i ->
    val hit = candidates[i / 3].hit
    when (i % 3) {
      0 -> hit.x
      1 -> hit.y
      else -> hit.z
    }
  }
}

/**
 * Номер кандидата под нажатием ([px], [py]) на виде [width] × [height]: кружки стоят там же, где их рисует сцена
 * ([candidateMarkShifts]). Выбранный [selected] лежит поверх соседей и проверяется первым, остальные — сверху вниз.
 * [density] — пикселей в dp. Мимо кружков — null.
 */
fun candidateAt(
  controller: SceneCameraController, hits: FloatArray, selected: Int, px: Float, py: Float, width: Float, height: Float, density: Float,
): Int? {
  val count = hits.size / 3
  val shifts = candidateMarkShifts(hits)
  val radius = (CANDIDATE_MARK_DP / 2 + TAP_SLACK_DP) * density
  val order = listOfNotNull(selected.takeIf { it in 0 until count }) + (count - 1 downTo 0).filter { it != selected }
  for (i in order) {
    val (x, y) = controller.project(hits[i * 3], hits[i * 3 + 1], hits[i * 3 + 2], width, height) ?: continue
    if (hypot(px - (x + shifts[i] * density), py - (y - CANDIDATE_SHIFT_DP * density)) <= radius) return i
  }
  return null
}

// запас вокруг кружка для пальца, dp
private const val TAP_SLACK_DP = 4f
