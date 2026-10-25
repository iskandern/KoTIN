package dev.iskandern.kotin

import dev.iskandern.kotin.model.PointMark
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.scene.ATLAS_FONT_PX
import dev.iskandern.kotin.scene.ATLAS_SDF_RADIUS
import dev.iskandern.kotin.scene.Glyph
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.MARKER_CELL_PX
import dev.iskandern.kotin.scene.MARKER_DEFAULT_DP
import dev.iskandern.kotin.scene.MARKER_HALO_PX_PER_DP
import dev.iskandern.kotin.scene.MARKER_INSET
import dev.iskandern.kotin.scene.MARKER_PLUS
import dev.iskandern.kotin.scene.MARKER_QUAD_PX
import dev.iskandern.kotin.scene.MARKER_SIZE_PX
import dev.iskandern.kotin.scene.POINT_HALO_DP
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.scene.buildTextMeshes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Значки точек сцены: квад меньше ячейки атласа — без поля под ореол выбранной точки, фигура того же размера. */
class PointMarksMeshTest {
  // ячейка значка — четверть атласа по u, весь по v
  private val atlas = GlyphAtlas(64, 64, ByteArray(0), mapOf(MARKER_PLUS to Glyph(0.25f, 0f, 0.5f, 1f, MARKER_CELL_PX, 0f)), 12, 9f)

  private fun mesh() = buildTextMeshes(
    buildRenderScene(SceneGeometry(emptyList(), emptyList(), emptyList(), emptyList(), listOf(PointMark("0", 7, 1.0, 2.0, 3.0)), emptyList(), false)),
    atlas,
  ).points.single()

  @Test
  fun `квад точки - внутренняя часть ячейки, размер фигуры прежний`() {
    val m = mesh()
    assertEquals(1, m.quadCount)
    // углы: 0 — верх-лево, 2 — низ-право
    assertEquals(-MARKER_QUAD_PX / 2f, m.corners[0])
    assertEquals(MARKER_QUAD_PX / 2f, m.corners[1])
    assertEquals(MARKER_QUAD_PX / 2f, m.corners[2 * 4])
    assertEquals(-MARKER_QUAD_PX / 2f, m.corners[2 * 4 + 1])
    assertTrue(MARKER_QUAD_PX < MARKER_CELL_PX)
    // высота — от номинальной стороны: на экране фигура той же величины, что и при кваде во всю ячейку
    assertEquals(-MARKER_DEFAULT_DP * ATLAS_FONT_PX / MARKER_SIZE_PX, m.corners[2], 1e-5f)
    assertEquals(-1f, m.corners[3])
    // UV обрезаны на ту же долю с каждой стороны ячейки (v у материала перевёрнут)
    val cut = (MARKER_CELL_PX - MARKER_QUAD_PX) / 2f / MARKER_CELL_PX
    assertEquals(0.25f + 0.25f * cut, m.uvs[0], 1e-6f)
    assertEquals(1f - cut, m.uvs[1], 1e-6f)
    assertEquals(0.5f - 0.25f * cut, m.uvs[2 * 2], 1e-6f)
    assertEquals(cut, m.uvs[2 * 2 + 1], 1e-6f)
  }

  @Test
  fun `в кваде помещаются фигура, белый ореол и сглаживание`() {
    // фигура: радиус с отступом MARKER_INSET и полштриха r/10; ореол — POINT_HALO_DP, не больше MARKER_HALO_PX_PER_DP на dp;
    // сглаживание — до половины радиуса SDF букв (TEXT_VERTEX)
    val figure = (MARKER_SIZE_PX / 2f - MARKER_INSET) * 1.1f
    val halo = POINT_HALO_DP * MARKER_HALO_PX_PER_DP + 0.5f * ATLAS_SDF_RADIUS
    assertTrue(MARKER_QUAD_PX / 2f - figure >= halo + 1)
  }
}
