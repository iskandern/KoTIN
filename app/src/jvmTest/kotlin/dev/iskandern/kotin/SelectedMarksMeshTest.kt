package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.POINT_MODE_UNSET
import dev.iskandern.kotin.scene.ATLAS_FONT_PX
import dev.iskandern.kotin.scene.Glyph
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.MARKER_CELL_PX
import dev.iskandern.kotin.scene.MARKER_CIRCLE
import dev.iskandern.kotin.scene.MARKER_CROSS
import dev.iskandern.kotin.scene.MARKER_DEFAULT_DP
import dev.iskandern.kotin.scene.MARKER_DOT
import dev.iskandern.kotin.scene.MARKER_PLUS
import dev.iskandern.kotin.scene.MARKER_SIZE_PX
import dev.iskandern.kotin.scene.RenderPoint
import dev.iskandern.kotin.scene.TextLayerMesh
import dev.iskandern.kotin.scene.buildSelectedMarksMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Значки выбранного: свой набор по $PDMODE, без растяжения; ореолы — проходы материала. */
class SelectedMarksMeshTest {

  // у каждого значка своя ячейка: по u0 видно, какой глиф в кваде
  private val chars = listOf(MARKER_DOT, MARKER_PLUS, MARKER_CROSS, MARKER_CIRCLE)
  private val atlas = GlyphAtlas(
    64, 64, ByteArray(0), chars.withIndex().associate { (i, c) -> c to Glyph(i / 8f, 0f, (i + 1) / 8f, 1f, MARKER_CELL_PX, 0f) }, 12, 9f,
  )
  private val point = RenderPoint("0", 0xFF0000, 1f, 2f, 3f, alongX = 0f, alongZ = 1f)

  private fun glyphsOf(mesh: TextLayerMesh) = (0 until mesh.quadCount).map { chars[(mesh.uvs[it * 8] * 8 + 0.5f).toInt()] }

  @Test
  fun `крест и крест в круге - тем же набором, что в сцене`() {
    val plus = assertNotNull(buildSelectedMarksMesh(listOf(point), FloatArray(0), 2, 0f, 0x15202B, atlas))
    assertEquals(listOf(MARKER_PLUS), glyphsOf(plus))
    val composite = assertNotNull(buildSelectedMarksMesh(listOf(point), FloatArray(0), 35, 0f, 0x15202B, atlas))
    assertEquals(listOf(MARKER_CROSS, MARKER_CIRCLE), glyphsOf(composite))
    // цвет — заданный, а не точки; поворот — как у точки
    assertEquals(0x15.toByte(), composite.colors[0])
    assertEquals(0x20.toByte(), composite.colors[1])
    assertEquals(0f, composite.along[0])
    assertEquals(1f, composite.along[1])
  }

  @Test
  fun `размер - как у значков сцены, квад во всю ячейку с полем SDF`() {
    val byDefault = assertNotNull(buildSelectedMarksMesh(listOf(point), FloatArray(0), POINT_MODE_UNSET, 0f, 0, atlas))
    assertEquals(-MARKER_DEFAULT_DP * ATLAS_FONT_PX / MARKER_SIZE_PX, byDefault.corners[2], 1e-5f)
    assertEquals(-1f, byDefault.corners[3])
    assertEquals(-MARKER_CELL_PX / 2f, byDefault.corners[0])
    // $PDSIZE > 0 — в метрах, как у точек сцены
    val sized = assertNotNull(buildSelectedMarksMesh(listOf(point), FloatArray(0), 2, 0.5f, 0, atlas))
    assertEquals(0.5f * 1.25f * ATLAS_FONT_PX / MARKER_SIZE_PX, sized.corners[2], 1e-5f)
  }

  @Test
  fun `подписи без геометрии и точки без фигуры - точкой естественного размера`() {
    val mesh = assertNotNull(buildSelectedMarksMesh(listOf(point), floatArrayOf(4f, 5f, 6f), 1, 2f, 0, atlas))
    assertEquals(listOf(MARKER_DOT, MARKER_DOT), glyphsOf(mesh))
    // у якоря подписи — размер значка по умолчанию, а не растянутая точка
    assertEquals(-MARKER_DEFAULT_DP * ATLAS_FONT_PX / MARKER_SIZE_PX, mesh.corners[16 + 2], 1e-5f)
    assertEquals(4f, mesh.positions[12])
  }

  @Test
  fun `нет ни точек, ни якорей - нет и сетки`() {
    assertNull(buildSelectedMarksMesh(emptyList(), FloatArray(0), 2, 0f, 0, atlas))
    assertNull(buildSelectedMarksMesh(emptyList(), floatArrayOf(0f, 0f, 0f), 2, 0f, 0, GlyphAtlas(64, 64, ByteArray(0), emptyMap(), 12, 9f)))
  }
}
