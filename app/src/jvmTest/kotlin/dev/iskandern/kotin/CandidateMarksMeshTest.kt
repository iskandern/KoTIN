package dev.iskandern.kotin

import dev.iskandern.kotin.scene.ATLAS_FONT_PX
import dev.iskandern.kotin.scene.CANDIDATE_DIGIT_DP
import dev.iskandern.kotin.scene.CANDIDATE_MARK_DP
import dev.iskandern.kotin.scene.CANDIDATE_SHIFT_DP
import dev.iskandern.kotin.scene.Glyph
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.MARKER_CELL_PX
import dev.iskandern.kotin.scene.MARKER_DISC
import dev.iskandern.kotin.scene.MARKER_INSET
import dev.iskandern.kotin.scene.MARKER_SIZE_PX
import dev.iskandern.kotin.scene.buildCandidateMarksMesh
import dev.iskandern.kotin.scene.candidateMarkShifts
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Номера кандидатов выбора в сцене: кружок с номером у места попадания, выбранный — поверх. */
class CandidateMarksMeshTest {
  private val digit = Glyph(0.5f, 0f, 0.6f, 0.5f, 30, 20f)
  private val atlas = GlyphAtlas(
    64, 64, ByteArray(0),
    mapOf(MARKER_DISC to Glyph(0f, 0f, 0.25f, 1f, MARKER_CELL_PX, 0f), '1' to digit, '2' to digit, '3' to digit),
    40, 30f,
  )

  @Test
  fun `кандидаты в одной точке - в ряд, в разных - на своих точках`() {
    val hits = floatArrayOf(0f, 0f, 0f, 5f, 0f, 0f, 0f, 0f, 0f)
    val step = CANDIDATE_MARK_DP + 3f
    assertContentEquals(floatArrayOf(CANDIDATE_SHIFT_DP, CANDIDATE_SHIFT_DP, CANDIDATE_SHIFT_DP + step), candidateMarkShifts(hits))
  }

  @Test
  fun `кружок - диаметр в dp, номер горизонтален, выбранный последним`() {
    val hits = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f)
    val m = buildCandidateMarksMesh(hits, 0, 0x111111, 0xFFFFFF, 0xF5C400, 0x111111, atlas)!!
    // три кружка и три цифры
    assertEquals(6, m.quadCount)
    // первым — кандидат 2 (вторая тройка), последним — выбранный 1
    assertEquals(4f, m.positions[0])
    assertEquals(1f, m.positions[4 * 4 * 3])
    // кружок выбранного жёлтый, его номер тёмный
    assertEquals(0xF5.toByte(), m.colors[4 * 4 * 4])
    assertEquals(0x11.toByte(), m.colors[5 * 4 * 4])
    // высота кружка: диаметр фигуры (px атласа) — CANDIDATE_MARK_DP
    val radius = MARKER_SIZE_PX / 2f - MARKER_INSET
    val discHeight = -ATLAS_FONT_PX * CANDIDATE_MARK_DP / (2 * radius)
    assertEquals(discHeight, m.corners[2], 1e-5f)
    assertEquals(-1f, m.corners[3])
    // центр кружка — сдвиг вправо и вверх, в px атласа кружка
    val perDp = 2 * radius / CANDIDATE_MARK_DP
    assertEquals(CANDIDATE_SHIFT_DP * perDp, (m.corners[0] + m.corners[2 * 4]) / 2, 1e-4f)
    assertEquals(CANDIDATE_SHIFT_DP * perDp, (m.corners[1] + m.corners[2 * 4 + 1]) / 2, 1e-4f)
    // номер — кегль в dp, не прореживается; направление нулевое — горизонтально на экране
    assertEquals(-CANDIDATE_DIGIT_DP, m.corners[4 * 4 + 2])
    assertEquals(0f, m.along[4 * 2])
    assertEquals(0f, m.along[4 * 2 + 1])
  }

  @Test
  fun `один кандидат - номеров нет`() {
    assertNull(buildCandidateMarksMesh(floatArrayOf(0f, 0f, 0f), 0, 0, 0, 0, 0, atlas))
  }
}
