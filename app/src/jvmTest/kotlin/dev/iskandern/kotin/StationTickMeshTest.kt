package dev.iskandern.kotin

import dev.iskandern.kotin.dxf.FACE_DEFAULT
import dev.iskandern.kotin.model.NO_TICK
import dev.iskandern.kotin.model.TICK_DP
import dev.iskandern.kotin.scene.FACE_CAP_HEIGHT
import dev.iskandern.kotin.scene.ATLAS_FONT_PX
import dev.iskandern.kotin.scene.Glyph
import dev.iskandern.kotin.scene.GlyphAtlas
import dev.iskandern.kotin.scene.MARKER_FILL
import dev.iskandern.kotin.scene.RenderLabel
import dev.iskandern.kotin.scene.TextLayerMesh
import dev.iskandern.kotin.scene.buildRenderScene
import dev.iskandern.kotin.scene.buildTextMeshes
import dev.iskandern.kotin.model.SceneGeometry
import dev.iskandern.kotin.model.SceneLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Засечка пикета (T12): квад от точки вверх на TICK_DP, строка — над ней, всё в px атласа при кегле в dp. */
class StationTickMeshTest {
  private val glyph = Glyph(0f, 0f, 1f, 1f, 32, 20f)
  private val atlas = GlyphAtlas(1, 1, ByteArray(4), mapOf('П' to glyph, 'К' to glyph, '3' to glyph, MARKER_FILL to glyph), 50, 40f)

  private fun mesh(lift: Double, tick: Int): TextLayerMesh {
    // кегль 13 dp: высота подписи — заглавные
    val label = SceneLabel("L", 0x1440B8, 0.0, 0.0, 0.0, -13.0 * FACE_CAP_HEIGHT[FACE_DEFAULT], 0.0, "ПК3", alignH = 1, alignV = 1,
      screenHorizontal = true, liftDp = lift, tickRgb = tick)
    val geometry = SceneGeometry(emptyList(), emptyList(), emptyList(), listOf(label), emptyList(), emptyList(), false)
    return buildTextMeshes(buildRenderScene(geometry), atlas).labels.single()
  }

  // нижний и верхний край квадов (y угла от точки, px атласа): первый квад — засечка, остальные — буквы
  private fun quadY(mesh: TextLayerMesh, q: Int) = (0 until 4).map { mesh.corners[(q * 4 + it) * 4 + 1] }

  @Test
  fun `засечка от точки, строка над ней`() {
    val m = mesh(15.0, 0x1F5EFF)
    assertEquals(4, m.quadCount)
    // px атласа на dp: кегль атласа / кегль подписи
    val k = ATLAS_FONT_PX / 13f
    val tick = quadY(m, 0)
    assertEquals(0f, tick.min(), 1e-3f)
    assertEquals(TICK_DP * k, tick.max(), 1e-3f)
    // засечка — посередине точки вставки, её цвет — свой
    val xs = (0 until 4).map { m.corners[it * 4] }
    assertEquals(0f, xs.min() + xs.max(), 1e-3f)
    assertEquals(0x1F.toByte(), m.colors[0])
    // буквы — выше засечки
    for (q in 1 until m.quadCount) assertTrue(quadY(m, q).max() > TICK_DP * k)
  }

  @Test
  fun `без засечки и подъёма - как раньше`() {
    val m = mesh(0.0, NO_TICK)
    assertEquals(3, m.quadCount)
  }
}
